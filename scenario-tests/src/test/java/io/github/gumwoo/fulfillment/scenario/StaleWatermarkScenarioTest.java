package io.github.gumwoo.fulfillment.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gumwoo.fulfillment.reconciler.job.FailedWebhookRecoveryJob;
import io.github.gumwoo.fulfillment.reconciler.job.ReconciliationJob;
import io.github.gumwoo.fulfillment.scenario.support.App;
import io.github.gumwoo.fulfillment.scenario.support.MockShopby;
import io.github.gumwoo.fulfillment.scenario.support.ScenarioStack;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * F-06: watermark가 7일보다 오래되면 실패 웹훅 조회 대신 경보를 남기고 STATUS_CHANGE 재조회로 복구한다.
 */
class StaleWatermarkScenarioTest {

    ScenarioStack stack = ScenarioStack.get();
    MockShopby mock;

    @BeforeEach
    void setUp() {
        stack.use(Set.of(App.MOCK, App.INBOUND, App.WORKER, App.RECONCILER));
        stack.reset();
        mock = new MockShopby(stack.mockPort());
        mock.reset();
    }

    @Test
    void watermark가_7일보다_오래되면_실패웹훅_조회없이_경보를_남기고_재조회로_복구한다() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul")).withNano(0);
        setWatermark(now.minusDays(8));
        // 웹훅 전부 유실(실패 내역에는 남음)
        mock.webhook(stack.inboundWebhookUrl(), 1.0, true, 1, 7);
        createOrders(30_000, 5);

        FailedWebhookRecoveryJob.Result result = stack.bean(App.RECONCILER, FailedWebhookRecoveryJob.class).run();
        stack.awaitQueueDrained(Duration.ofSeconds(60));

        assertThat(result.staleWatermark()).isTrue();
        assertThat(result.enqueued()).isEqualTo(5);
        assertThat(failedWebhookApiCalls()).as("7일 넘는 구간은 실패 웹훅 API로 조회할 수 없으므로 호출하지 않는다").isZero();
        assertThat(stack.jdbc().queryForList("select type, target_key from alert"))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a).containsEntry("type", "STALE_WATERMARK");
                    assertThat(a).containsEntry("target_key", "FAILED_WEBHOOK");
                });
        assertThat(stack.bean(App.RECONCILER, ReconciliationJob.class).run().mismatches()).isZero();
        LocalDateTime advanced = stack.jdbc().queryForObject(
                "select last_success_at from sync_watermark where source = 'FAILED_WEBHOOK'", LocalDateTime.class);
        assertThat(advanced).isAfterOrEqualTo(now);
    }

    @Test
    void watermark가_7일_이내면_경보_없이_실패웹훅_조회로_복구한다() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul")).withNano(0);
        setWatermark(now.minusDays(6));
        mock.webhook(stack.inboundWebhookUrl(), 1.0, true, 1, 7);
        createOrders(31_000, 5);

        FailedWebhookRecoveryJob.Result result = stack.bean(App.RECONCILER, FailedWebhookRecoveryJob.class).run();
        stack.awaitQueueDrained(Duration.ofSeconds(60));

        assertThat(result.staleWatermark()).isFalse();
        assertThat(result.failedWebhooks()).isEqualTo(5);
        assertThat(failedWebhookApiCalls()).isPositive();
        assertThat(stack.jdbc().queryForObject("select count(*) from alert", Long.class)).isZero();
        assertThat(stack.bean(App.RECONCILER, ReconciliationJob.class).run().mismatches()).isZero();
    }

    private long failedWebhookApiCalls() {
        return mock.stats().path("callsByPath").path("GET /webhooks/failed").asLong(0);
    }

    private void setWatermark(LocalDateTime at) {
        // 테이블은 reconciler 기동 시 ddl-auto로 이미 있다
        stack.jdbc().update("insert into sync_watermark (source, last_success_at) values ('FAILED_WEBHOOK', ?)", at);
    }

    private void createOrders(long baseOptionNo, int n) {
        for (int i = 0; i < n; i++) {
            long optionNo = baseOptionNo + i;
            mock.createOrder("2026111102" + optionNo, 800_000 + optionNo, "PAY_DONE", true,
                    List.of(MockShopby.option(optionNo, "색상", "흰색")));
        }
    }
}
