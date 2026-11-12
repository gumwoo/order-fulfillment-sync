package io.github.gumwoo.fulfillment.scenario.measure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.gumwoo.fulfillment.core.cancel.CancelService;
import io.github.gumwoo.fulfillment.core.shipment.InvoiceSyncService;
import io.github.gumwoo.fulfillment.reconciler.job.FailedWebhookRecoveryJob;
import io.github.gumwoo.fulfillment.reconciler.job.ReconciliationJob;
import io.github.gumwoo.fulfillment.reconciler.job.StatusChangeRequeryJob;
import io.github.gumwoo.fulfillment.scenario.support.App;
import io.github.gumwoo.fulfillment.scenario.support.MockShopby;
import io.github.gumwoo.fulfillment.scenario.support.ScenarioStack;
import io.github.gumwoo.fulfillment.scenario.support.SqsProbe;
import io.github.gumwoo.fulfillment.worker.job.TplEventPollJob;
import io.github.gumwoo.fulfillment.worker.message.ProcessingStats;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 장애 주입 측정 (실행 계획 0001 완료 조건). ./gradlew :scenario-tests:measure 로만 돌린다(scenarioTest에서 제외).
 * 결과는 scenario-tests/build/measurements/*.json에 남기고, 사람이 docs/measurements.md에 옮긴다. 숫자는 실행할 때마다 다를 수 있다.
 */
class FaultInjectionMeasurement {

    static final Path OUT = Path.of("build", "measurements");

    ScenarioStack stack = ScenarioStack.get();
    MockShopby mock;
    ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @BeforeEach
    void setUp() {
        Map<App, Map<String, Object>> apps = new LinkedHashMap<>();
        apps.put(App.MOCK, Map.of());
        apps.put(App.INBOUND, Map.of());
        apps.put(App.WORKER, Map.of("app.worker.fulfillment-enabled", "true", "app.sqs.consumer-threads", "4",
                "app.worker.release-wait", "PT2S"));
        apps.put(App.RECONCILER, Map.of());
        stack.use(apps);
        stack.reset();
        mock = new MockShopby(stack.mockPort());
        mock.reset();
        stack.bean(App.WORKER, ProcessingStats.class).reset();
    }

    /** 웹훅 유실·중복, 429·EXHAUSTED, 3PL 출고·송장을 한 번에 넣고 복구 경로가 불일치를 0으로 만드는 과정을 잰다 */
    @Test
    void 장애_주입_종합() throws Exception {
        int orders = 300;
        List<Long> options = LongStream.range(80_000, 80_000 + orders).boxed().toList();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("orders", orders);
        r.put("faults", Map.of("webhookDrop", "30% (앞 절반은 실패 내역에 남음, 뒤 절반은 흔적 없음)",
                "webhookDuplicates", 2, "shopby429", 50, "shopbyExhausted", 50));
        ProcessingStats stats = stack.bean(App.WORKER, ProcessingStats.class);

        long t0 = System.currentTimeMillis();
        mock.rateLimitFaults(50, 50, 0);
        mock.webhook(stack.inboundWebhookUrl(), 0.3, true, 2, 11);
        createOrders(options.subList(0, orders / 2));
        sleep(2100);
        mock.webhook(stack.inboundWebhookUrl(), 0.3, false, 2, 12);
        createOrders(options.subList(orders / 2, orders));
        stack.awaitQueueDrained(Duration.ofSeconds(180));
        r.put("phase1_webhookOnly_ms", System.currentTimeMillis() - t0);

        JsonNode ms = mock.stats();
        r.put("webhooksSent", ms.path("webhooksSent").asLong());
        r.put("webhooksDropped", ms.path("webhooksDropped").asLong());
        ReconciliationJob reconcile = stack.bean(App.RECONCILER, ReconciliationJob.class);
        ScenarioStack.passCurrentSecond();
        r.put("mismatch_beforeRecovery", reconcile.run().mismatches());
        r.put("tplRegistered_beforeRecovery", countTpl(options));

        long recoveryStart = System.currentTimeMillis();
        stack.bean(App.RECONCILER, FailedWebhookRecoveryJob.class).run();
        stack.awaitQueueDrained(Duration.ofSeconds(180));
        ScenarioStack.passCurrentSecond();
        r.put("mismatch_afterFailedWebhookRecovery", reconcile.run().mismatches());
        stack.bean(App.RECONCILER, StatusChangeRequeryJob.class).run();
        stack.awaitQueueDrained(Duration.ofSeconds(180));
        ScenarioStack.passCurrentSecond();
        int afterRequery = reconcile.run().mismatches();
        r.put("mismatch_afterStatusChangeRequery", afterRequery);
        r.put("recovery_ms", System.currentTimeMillis() - recoveryStart);
        r.put("tplRegistered_afterRecovery", countTpl(options));

        // 3PL 출고·송장 → 송장 반영
        long t1 = System.currentTimeMillis();
        for (long no : options) {
            mock.tplAdvance(no, "RELEASED");
            mock.tplInvoice(no, "INV-" + no);
        }
        stack.bean(App.WORKER, TplEventPollJob.class).poll();
        await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofSeconds(1)).until(() ->
                stack.jdbc().queryForObject("select count(*) from shipment where tpl_status='RELEASED' and invoice_no is not null",
                        Long.class) == orders);
        InvoiceSyncService sync = stack.bean(App.WORKER, InvoiceSyncService.class);
        int invoiceRequests = 0;
        while (sync.syncPending().sent() > 0) {
            invoiceRequests++;
        }
        stack.awaitQueueDrained(Duration.ofSeconds(120));
        r.put("invoice_ms", System.currentTimeMillis() - t1);
        r.put("invoiceRequests", invoiceRequests);
        // 송장 단계에서도 웹훅 30% 유실이 계속된다. 배송중 변경 웹훅(100건 단위)이 버려지면 그만큼 불일치가 남으므로
        // 운영과 같이 재조회로 복구한 뒤의 값을 최종으로 본다. 복구 전 값도 함께 남긴다
        ScenarioStack.passCurrentSecond();
        r.put("mismatch_afterInvoice_beforeRecovery", reconcile.run().mismatches());
        stack.bean(App.RECONCILER, StatusChangeRequeryJob.class).run();
        stack.awaitQueueDrained(Duration.ofSeconds(120));
        ScenarioStack.passCurrentSecond();
        r.put("mismatch_final", reconcile.run().mismatches());
        r.put("shopbyDeliveryIng", StreamSupport.stream(mock.options().spliterator(), false)
                .filter(o -> "DELIVERY_ING".equals(o.path("orderStatusType").asText())).count());

        JsonNode finalStats = mock.stats();
        r.put("shopbyMaxCallsPerSecond", finalStats.path("maxCallsInAnySecond").asInt());
        r.put("shopby429", finalStats.path("rejected429").asLong());
        r.put("dlq", SqsProbe.approximateTotal(stack.sqs(), stack.queueUrl(ScenarioStack.DLQ)));
        r.put("conflicts", stack.jdbc().queryForList("select reason_code, count(*) n from conflict group by reason_code"));
        r.put("tplDuplicateRegistrations", countDuplicateRegistrations());
        r.put("workerStats", stats.snapshot());
        write("fault-injection.json", r);

        assertThat(afterRequery).isZero();
    }

    /** 출고요청·출고작업·피킹 단계에서 각 20건 취소: 취소했는데 출고된 건이 0인지 */
    @Test
    void 취소_경합() throws Exception {
        List<Long> requested = LongStream.range(81_000, 81_020).boxed().toList();
        List<Long> working = LongStream.range(81_100, 81_120).boxed().toList();
        List<Long> picked = LongStream.range(81_200, 81_220).boxed().toList();
        List<Long> all = new ArrayList<>();
        all.addAll(requested);
        all.addAll(working);
        all.addAll(picked);
        mock.webhook(stack.inboundWebhookUrl(), 0, false, 1, 1);
        createOrders(all);
        await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofSeconds(1)).until(() ->
                all.stream().allMatch(no -> "PRODUCT_PREPARE".equals(mock.option(no).path("orderStatusType").asText())));
        working.forEach(no -> mock.tplAdvance(no, "WORKING"));
        picked.forEach(no -> {
            mock.tplAdvance(no, "WORKING");
            mock.tplAdvance(no, "PICKED");
        });

        long t0 = System.currentTimeMillis();
        all.forEach(no -> mock.changeClaim(no, "CANCEL_REQUEST", 990_000 + no, true));
        await().atMost(Duration.ofSeconds(60)).until(() ->
                stack.jdbc().queryForObject("select count(*) from cancel_case where state in ('HOLD_PENDING','DONE','RECALL_REQUIRED')",
                        Long.class) == all.size());
        CancelService cancel = stack.bean(App.WORKER, CancelService.class);
        CancelService.HoldResult hold = cancel.holdPending();
        cancel.resume();
        await().atMost(Duration.ofSeconds(60)).until(() ->
                stack.jdbc().queryForObject("select count(*) from cancel_case where state in ('DONE','RECALL_REQUIRED')",
                        Long.class) == all.size());
        long elapsed = System.currentTimeMillis() - t0;

        long canceledButShipped = all.stream().filter(no -> {
            String claim = mock.option(no).path("claimStatusType").asText("");
            String tpl = mock.tplOrder(no).path("status").asText();
            return "CANCEL_DONE".equals(claim) && !"CANCELED".equals(tpl);
        }).count();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("cancels", Map.of("atRequested", requested.size(), "atWorking", working.size(), "atPicked", picked.size()));
        r.put("cancelStates", stack.jdbc().queryForList("select state, count(*) n, sum(fee) fee from cancel_case group by state"));
        r.put("holdCalls", mock.stats().path("holdCalls"));
        r.put("holdResult", hold);
        r.put("recallTasks", stack.jdbc().queryForObject("select count(*) from recall_task", Long.class));
        r.put("canceledButShipped", canceledButShipped);
        r.put("elapsed_ms", elapsed);
        write("cancel-race.json", r);

        assertThat(canceledButShipped).isZero();
    }

    /** 웹훅 수신 부하(k6) 후, 수신한 변경이 모두 반영될 때까지 걸리는 시간 */
    @Test
    void 수신_부하_k6() throws Exception {
        String vus = System.getProperty("k6.vus", "20");
        String duration = System.getProperty("k6.duration", "30s");
        // 수신만 잰다: 출고 연동은 끈다(3PL·샵바이 호출 없음)
        Map<App, Map<String, Object>> apps = new LinkedHashMap<>();
        apps.put(App.INBOUND, Map.of());
        apps.put(App.WORKER, Map.of("app.sqs.consumer-threads", "4"));
        stack.use(apps);
        stack.reset();
        ProcessingStats stats = stack.bean(App.WORKER, ProcessingStats.class);
        stats.reset();
        File summary = OUT.resolve("k6-summary.json").toFile();
        Files.createDirectories(OUT);
        File script = new File("../load-test/webhook-ingest.js");
        Process p = new ProcessBuilder("k6", "run", "--quiet", "-e", "BASE_URL=" + stack.baseUrl(App.INBOUND),
                "-e", "VUS=" + vus, "-e", "DURATION=" + duration, "-e", "OUT=" + summary.getAbsolutePath(),
                script.getAbsolutePath()).inheritIO().start();
        long k6End;
        assertThat(p.waitFor()).isZero();
        k6End = System.currentTimeMillis();
        JsonNode k6 = mapper.readTree(summary);
        long requests = k6.path("metrics").path("http_reqs").path("values").path("count").asLong();
        long expectedApplied = requests * 5;
        // 30초마다 진행을 남기고, 시간을 넘겨도 어디까지 반영됐는지 기록한 뒤 실패시킨다
        long deadline = k6End + Duration.ofMinutes(10).toMillis();
        long nextLog = k6End;
        boolean drained = false;
        while (System.currentTimeMillis() < deadline) {
            if (stats.count("WEBHOOK", "APPLIED") >= expectedApplied) {
                drained = true;
                break;
            }
            if (System.currentTimeMillis() >= nextLog) {
                System.out.printf("[ingest] +%ds applied=%d/%d duplicate=%d stale=%d conflict=%d queue=%d%n",
                        (System.currentTimeMillis() - k6End) / 1000, stats.count("WEBHOOK", "APPLIED"), expectedApplied,
                        stats.count("WEBHOOK", "DUPLICATE"), stats.count("WEBHOOK", "STALE"), stats.count("WEBHOOK", "CONFLICT"),
                        SqsProbe.approximateTotal(stack.sqs(), stack.queueUrl(ScenarioStack.QUEUE)));
                nextLog += 30_000;
            }
            Thread.sleep(1000);
        }
        long drainMs = System.currentTimeMillis() - k6End;

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("vus", vus);
        r.put("duration", duration);
        JsonNode m = k6.path("metrics");
        r.put("http_reqs", requests);
        r.put("http_reqs_per_s", m.path("http_reqs").path("values").path("rate").asDouble());
        r.put("http_req_duration_ms", m.path("http_req_duration").path("values"));
        r.put("http_req_failed_rate", m.path("http_req_failed").path("values").path("rate").asDouble());
        r.put("elements", expectedApplied);
        r.put("applied", stats.count("WEBHOOK", "APPLIED"));
        r.put("drainAfterLoad_ms", drainMs);
        r.put("dlq", SqsProbe.approximateTotal(stack.sqs(), stack.queueUrl(ScenarioStack.DLQ)));
        r.put("drained", drained);
        write("ingest-load.json", r);
        assertThat(drained).as("부하 종료 후 10분 안에 모두 반영").isTrue();
    }

    private void createOrders(List<Long> options) {
        for (long no : options) {
            mock.createOrder("20261111" + no, 950_000 + no, "PAY_DONE", true, List.of(MockShopby.option(no, "색상", "검정")));
        }
    }

    private long countTpl(List<Long> options) {
        JsonNode reg = mock.tplStats().path("registerCalls");
        return options.stream().filter(no -> reg.has(String.valueOf(no))).count();
    }

    private long countDuplicateRegistrations() {
        JsonNode reg = mock.tplStats().path("registerCalls");
        long n = 0;
        for (var it = reg.fields(); it.hasNext(); ) {
            if (it.next().getValue().asInt() > 1) {
                n++;
            }
        }
        return n;
    }

    private void write(String name, Map<String, Object> result) throws Exception {
        Files.createDirectories(OUT);
        result.put("measuredAt", java.time.LocalDateTime.now().toString());
        result.put("environment", Map.of("os", System.getProperty("os.name"), "cpus", Runtime.getRuntime().availableProcessors(),
                "java", System.getProperty("java.version"), "infra", "Testcontainers: mysql 8.4.13, redis 7.4.12, localstack 4.14.0",
                "external", "mock-servers (샵바이·3PL mock)"));
        mapper.writeValue(OUT.resolve(name).toFile(), result);
        System.out.println("[측정] " + name + " " + mapper.writeValueAsString(result));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
