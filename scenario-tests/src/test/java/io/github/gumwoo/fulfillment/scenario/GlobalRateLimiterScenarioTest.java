package io.github.gumwoo.fulfillment.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.gumwoo.fulfillment.core.port.ShopbyClient;
import io.github.gumwoo.fulfillment.reconciler.job.StatusChangeRequeryJob;
import io.github.gumwoo.fulfillment.scenario.support.App;
import io.github.gumwoo.fulfillment.scenario.support.MockShopby;
import io.github.gumwoo.fulfillment.scenario.support.ScenarioStack;
import io.github.gumwoo.fulfillment.scenario.support.SqsProbe;
import io.github.gumwoo.fulfillment.worker.message.ProcessingStats;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * F-07: 인스턴스 3개가 동시에 호출해도 샵바이 mock 호출이 초당 80회를 넘지 않고,
 * 429와 EXHAUSTED를 받아도 DLQ로 가는 메시지가 0건이다.
 *
 * <p>worker 3개는 각각 별도 Spring 컨텍스트(별도 Redis 연결·HTTP 클라이언트)이고, 레이트 리미터는 Redis 토큰 버킷 하나를 공유한다 (ADR-0007).
 * <ul>
 *   <li>한도 검증: 전체 처리 흐름으로는 이 PC에서 초당 약 50~67회가 최대라(리미터를 풀어도 48회, 2026-11-11 측정)
 *       한도를 넘는 수요를 만들 수 없다. 그래서 세 인스턴스의 ShopbyClient를 직접 동시에 호출해 수요를 한도 이상으로 만든다.</li>
 *   <li>DLQ 검증: 실제 메시지 처리 흐름에서 429·EXHAUSTED를 주입한다.</li>
 * </ul>
 */
class GlobalRateLimiterScenarioTest {

    static final List<App> WORKERS = List.of(App.WORKER, App.WORKER_2, App.WORKER_3);
    static final int ORDERS = 400;
    static final int INJECTED_429 = 30;
    static final int INJECTED_EXHAUSTED = 30;

    ScenarioStack stack = ScenarioStack.get();
    MockShopby mock;

    @BeforeEach
    void setUp() {
        Map<String, Object> worker = Map.of("app.worker.fulfillment-enabled", "true", "app.sqs.consumer-threads", "4");
        Map<App, Map<String, Object>> apps = new LinkedHashMap<>();
        apps.put(App.MOCK, Map.of());
        apps.put(App.INBOUND, Map.of());
        WORKERS.forEach(w -> apps.put(w, worker));
        apps.put(App.RECONCILER, Map.of());
        stack.use(apps);
        stack.reset();
        mock = new MockShopby(stack.mockPort());
        mock.reset();
        WORKERS.forEach(w -> stack.bean(w, ProcessingStats.class).reset());
    }

    @Test
    void 세_인스턴스가_한도보다_많이_호출해도_어떤_1초에도_80회를_넘지_않는다() throws Exception {
        int threadsPerInstance = 16;
        Duration duration = Duration.ofSeconds(6);
        AtomicLong ok = new AtomicLong();
        AtomicLong limited = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(threadsPerInstance * WORKERS.size());
        List<Future<?>> futures = new ArrayList<>();
        long until = System.currentTimeMillis() + duration.toMillis();
        LocalDateTime to = LocalDateTime.now();
        for (App w : WORKERS) {
            ShopbyClient client = stack.bean(w, ShopbyClient.class);
            for (int t = 0; t < threadsPerInstance; t++) {
                futures.add(pool.submit(() -> {
                    while (System.currentTimeMillis() < until) {
                        try {
                            client.searchFailedWebhooks(to.minusHours(1), to, 1, 1);
                            ok.incrementAndGet();
                        } catch (ShopbyClient.RateLimitedException e) {
                            limited.incrementAndGet();
                        }
                    }
                }));
            }
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        JsonNode stats = mock.stats();
        int maxPerSecond = stats.path("maxCallsInAnySecond").asInt();
        int maxSliding = stats.path("maxCallsInAnySlidingSecond").asInt();
        assertThat(limited.get()).as("수요가 한도를 넘어 대기 한도 초과가 일어났다").isPositive();
        System.out.printf("[F-07 측정·한도] 슬라이딩 1초 최대 %d (mock 도착 시각 기준, 리미터 보장은 토큰 발급 시각 기준)%n", maxSliding);
        assertThat(maxPerSecond).as("어떤 1초 구간에서도 80회 이하, 그리고 한도 가까이 실제로 호출했다").isBetween(60, 80);
        System.out.printf("[F-07 측정·한도] 인스턴스 3 × 스레드 %d, %ds 동안 성공 %d, 대기 초과 %d, 1초 최대 %d%n",
                threadsPerInstance, duration.toSeconds(), ok.get(), limited.get(), maxPerSecond);
    }

    @Test
    void 처리_중_429와_EXHAUSTED를_받아도_DLQ로_가는_메시지가_0건이다() {
        // 재전송(Redeliver)이 한 번만 일어나도 DLQ로 가게 해서, 429를 재발행이 아닌 재전송으로 처리하면 바로 드러나게 한다
        setMaxReceiveCount(1);
        try {
            run429AndExhausted();
        } finally {
            setMaxReceiveCount(5);
        }
    }

    private void setMaxReceiveCount(int n) {
        var sqs = stack.sqs();
        String dlqArn = sqs.getQueueAttributes(b -> b.queueUrl(stack.queueUrl(ScenarioStack.DLQ))
                .attributeNames(software.amazon.awssdk.services.sqs.model.QueueAttributeName.QUEUE_ARN))
                .attributes().get(software.amazon.awssdk.services.sqs.model.QueueAttributeName.QUEUE_ARN);
        sqs.setQueueAttributes(b -> b.queueUrl(stack.queueUrl(ScenarioStack.QUEUE)).attributes(Map.of(
                software.amazon.awssdk.services.sqs.model.QueueAttributeName.REDRIVE_POLICY,
                "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"" + n + "\"}")));
    }

    private void run429AndExhausted() {
        mock.webhook(stack.inboundWebhookUrl(), 0, false, 1, 1);
        for (int i = 0; i < ORDERS; i++) {
            long optionNo = 40_000 + i;
            mock.createOrder("2026111104" + optionNo, 700_000 + optionNo, "PAY_DONE", false,
                    List.of(MockShopby.option(optionNo, "색상", "검정")));
        }
        ScenarioStack.passCurrentSecond();
        long started = System.currentTimeMillis();
        int enqueued = stack.bean(App.RECONCILER, StatusChangeRequeryJob.class).run();
        assertThat(enqueued).isGreaterThanOrEqualTo(ORDERS);
        // 재조회(GET /orders)가 아니라 worker의 호출이 429·EXHAUSTED를 받도록 큐 투입 뒤에 주입한다
        mock.rateLimitFaults(INJECTED_429, INJECTED_EXHAUSTED, 0);

        await().atMost(Duration.ofSeconds(180)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(preparedCount()).isEqualTo(ORDERS));
        long elapsedMs = System.currentTimeMillis() - started;
        stack.awaitQueueDrained(Duration.ofSeconds(60));

        JsonNode stats = mock.stats();
        int maxPerSecond = stats.path("maxCallsInAnySecond").asInt();
        long rejected = stats.path("rejected429").asLong();
        long prepareCalls = stats.path("callsByPath").path("PUT /orders/prepare-product").asLong();
        int dlq = SqsProbe.approximateTotal(stack.sqs(), stack.queueUrl(ScenarioStack.DLQ));
        long rateLimited = WORKERS.stream()
                .mapToLong(w -> stack.bean(w, ProcessingStats.class).count("SHOPBY", "RATE_LIMITED")).sum();
        long busyInstances = WORKERS.stream()
                .filter(w -> stack.bean(w, ProcessingStats.class).count("SHIPMENT", "REQUESTED") > 0).count();

        assertThat(rejected).as("주입한 429를 실제로 받았다").isEqualTo(INJECTED_429);
        assertThat(stats.path("exhaustedSent").asLong()).as("주입한 EXHAUSTED를 실제로 받았다").isEqualTo(INJECTED_EXHAUSTED);
        assertThat(rateLimited).as("429·EXHAUSTED·대기 초과는 재발행으로 처리됐다").isPositive();
        assertThat(dlq).as("DLQ로 간 메시지").isZero();
        // 처리 흐름은 이 PC에서 한도까지 차지 않는다(클래스 주석). 한도 검증은 위 테스트가 한다
        assertThat(maxPerSecond).isLessThanOrEqualTo(80);
        assertThat(busyInstances).as("세 인스턴스 모두 일했다").isEqualTo(3);

        System.out.printf("[F-07 측정·DLQ] 주문 %d, 상품준비중 호출 %d, 1초 최대 %d, 429 %d, EXHAUSTED 주입 %d, 재발행 %d, DLQ %d, 처리 시간 %.1fs%n",
                ORDERS, prepareCalls, maxPerSecond, rejected, INJECTED_EXHAUSTED, rateLimited, dlq, elapsedMs / 1000.0);
    }

    private long preparedCount() {
        return StreamSupport.stream(mock.options().spliterator(), false)
                .filter(o -> "PRODUCT_PREPARE".equals(o.path("orderStatusType").asText()))
                .count();
    }
}
