package io.github.gumwoo.fulfillment.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.gumwoo.fulfillment.reconciler.job.FailedWebhookRecoveryJob;
import io.github.gumwoo.fulfillment.reconciler.job.ReconciliationJob;
import io.github.gumwoo.fulfillment.reconciler.job.StatusChangeRequeryJob;
import io.github.gumwoo.fulfillment.scenario.support.App;
import io.github.gumwoo.fulfillment.scenario.support.MockShopby;
import io.github.gumwoo.fulfillment.scenario.support.ScenarioStack;
import io.github.gumwoo.fulfillment.worker.message.ProcessingStats;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * F-05: 웹훅 30%를 버려도 실패 웹훅 조회와 STATUS_CHANGE 재조회 후 불일치가 0이 된다.
 * 버린 웹훅의 절반 구간은 샵바이 실패 내역에 남기고(실패 웹훅 조회로 복구), 나머지는 흔적 없이 버린다(재조회로만 복구).
 */
class WebhookLossRecoveryScenarioTest {

    static final int ORDERS_PER_BATCH = 50;

    ScenarioStack stack = ScenarioStack.get();
    MockShopby mock;
    ProcessingStats stats;

    @BeforeEach
    void setUp() {
        // 실패 웹훅 복구 구간을 실패 시각 앞 1초로 좁힌다(운영 기본 5분). 두 배치를 시간으로 갈라
        // 어느 경로가 무엇을 복구했는지 따로 셀 수 있게 하기 위해서다.
        Map<App, Map<String, Object>> apps = new LinkedHashMap<>();
        apps.put(App.MOCK, Map.of());
        apps.put(App.INBOUND, Map.of());
        apps.put(App.WORKER, Map.of());
        apps.put(App.RECONCILER, Map.of("app.reconciler.failed-webhook-window-before", "PT1S",
                "app.reconciler.failed-webhook-window-after", "PT0S"));
        stack.use(apps);
        stack.reset();
        mock = new MockShopby(stack.mockPort());
        mock.reset();
        stats = stack.bean(App.WORKER, ProcessingStats.class);
        stats.reset();
    }

    @Test
    void 웹훅_30퍼센트를_버려도_복구_경로_후_불일치가_0이다() {
        // 1) 버린 웹훅을 실패 내역에 남기는 구간
        mock.webhook(stack.inboundWebhookUrl(), 0.3, true, 1, 42);
        createOrders(10_000);
        sleep(2100);
        // 2) 흔적 없이 버리는 구간 (샵바이 장애·네트워크 유실 등)
        mock.webhook(stack.inboundWebhookUrl(), 0.3, false, 1, 43);
        createOrders(20_000);

        JsonNode mockStats = mock.stats();
        long sent = mockStats.path("webhooksSent").asLong();
        long dropped = mockStats.path("webhooksDropped").asLong();
        double dropRate = (double) dropped / (sent + dropped);
        assertThat(dropRate).as("웹훅을 30% 확률로 버린다(시드 고정). 관측 비율이 크게 벗어나면 시나리오가 의도와 다르다")
                .isBetween(0.2, 0.4);
        stack.awaitQueueDrained(Duration.ofSeconds(60));

        ReconciliationJob reconcile = stack.bean(App.RECONCILER, ReconciliationJob.class);
        ReconciliationJob.Result before = reconcile.run();
        assertThat(before.compared()).isEqualTo(ORDERS_PER_BATCH * 2);
        assertThat(before.mismatches()).as("복구 전에는 유실로 인한 불일치가 있어야 한다").isPositive();

        FailedWebhookRecoveryJob.Result failedResult = stack.bean(App.RECONCILER, FailedWebhookRecoveryJob.class).run();
        assertThat(failedResult.staleWatermark()).isFalse();
        assertThat(failedResult.failedWebhooks()).isPositive();
        stack.awaitQueueDrained(Duration.ofSeconds(60));
        long recoveredByFailedWebhook = stats.count("FAILED_WEBHOOK", "APPLIED");
        // 실패 웹훅 조회만으로 실패 내역이 남은 구간(배치 1)은 완전히 복구되고, 흔적 없는 구간(배치 2)은 아직 남아 있어야 한다
        assertThat(mismatchedOptions(10_000)).as("배치 1: 실패 웹훅 경로로 전부 복구").isEmpty();
        assertThat(mismatchedOptions(20_000)).as("배치 2: 실패 내역이 없어 실패 웹훅 경로로는 복구되지 않는다").isNotEmpty();

        stack.bean(App.RECONCILER, StatusChangeRequeryJob.class).run();
        stack.awaitQueueDrained(Duration.ofSeconds(60));
        long recoveredByPolling = stats.count("POLLING", "APPLIED");

        ReconciliationJob.Result after = reconcile.run();
        assertThat(after.compared()).isEqualTo(ORDERS_PER_BATCH * 2);
        assertThat(after.mismatches()).isZero();
        assertThat(recoveredByFailedWebhook).as("실패 내역 구간은 실패 웹훅 조회로 복구된다").isPositive();
        assertThat(recoveredByPolling).as("흔적 없는 유실은 재조회로 복구된다").isPositive();

        System.out.printf("[F-05 측정] 웹훅 발송 %d, 버림 %d (%.1f%%), 복구 전 불일치 %d, 실패웹훅 경로 복구 %d, 재조회 경로 복구 %d, 복구 후 불일치 %d%n",
                sent, dropped, 100.0 * dropped / (sent + dropped), before.mismatches(), recoveredByFailedWebhook,
                recoveredByPolling, after.mismatches());
    }

    /** mock(샵바이)과 우리 DB의 상태가 다른 옵션 번호 */
    private List<Long> mismatchedOptions(long baseOptionNo) {
        List<Long> result = new java.util.ArrayList<>();
        for (int i = 0; i < ORDERS_PER_BATCH; i++) {
            long no = baseOptionNo + i;
            String theirs = mock.option(no).path("orderStatusType").asText();
            List<String> ours = stack.jdbc().queryForList(
                    "select status from order_line_state where order_product_option_no = ?", String.class, no);
            if (ours.isEmpty() || !ours.get(0).equals(theirs)) {
                result.add(no);
            }
        }
        return result;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 주문 50개: 결제완료(웹훅) → 상품준비중(웹훅). 옵션 하나씩 */
    private void createOrders(long baseOptionNo) {
        for (int i = 0; i < ORDERS_PER_BATCH; i++) {
            long optionNo = baseOptionNo + i;
            mock.createOrder("2026111101" + optionNo, 900_000 + optionNo, "PAY_DONE", true,
                    List.of(MockShopby.option(optionNo, "색상", "검정")));
            mock.changeStatus(optionNo, "PRODUCT_PREPARE", true);
        }
    }
}
