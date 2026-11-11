package io.github.gumwoo.fulfillment.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.gumwoo.fulfillment.reconciler.job.StatusChangeRequeryJob;
import io.github.gumwoo.fulfillment.scenario.support.App;
import io.github.gumwoo.fulfillment.scenario.support.MockShopby;
import io.github.gumwoo.fulfillment.scenario.support.ScenarioStack;
import io.github.gumwoo.fulfillment.worker.message.ProcessingStats;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * F-03: 같은 웹훅이 3번 들어오고 같은 변경이 재조회 경로로 한 번 더 들어와도 상태 반영은 1회다.
 */
class IdempotencyScenarioTest {

    static final long OPTION = 7001;

    ScenarioStack stack = ScenarioStack.get();
    MockShopby mock;
    ProcessingStats stats;

    @BeforeEach
    void setUp() {
        stack.use(Set.of(App.MOCK, App.INBOUND, App.WORKER, App.RECONCILER));
        stack.reset();
        mock = new MockShopby(stack.mockPort());
        mock.reset();
        stats = stack.bean(App.WORKER, ProcessingStats.class);
        stats.reset();
    }

    @Test
    void 같은_웹훅_3번과_재조회_1번이_와도_반영은_1회다() {
        mock.webhook(stack.inboundWebhookUrl(), 0, false, 3, 1);

        mock.createOrder("202611110000001", 5001, "PAY_DONE", true,
                List.of(MockShopby.option(OPTION, "색상|사이즈", "검정|L")));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(stats.count("WEBHOOK", "APPLIED")).isEqualTo(1);
            assertThat(stats.count("WEBHOOK", "DUPLICATE")).isEqualTo(2);
        });

        int enqueued = stack.bean(App.RECONCILER, StatusChangeRequeryJob.class).run();
        assertThat(enqueued).isEqualTo(1);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(stats.count("POLLING", "DUPLICATE")).isEqualTo(1));
        assertThat(stats.count("POLLING", "APPLIED")).isZero();
        assertThat(historyCount()).isEqualTo(1);
        assertThat(stack.jdbc().queryForObject("select count(*) from processed_event", Long.class)).isEqualTo(1);
        assertThat(stack.jdbc().queryForObject(
                "select status from order_line_state where order_product_option_no = ?", String.class, OPTION))
                .isEqualTo("PAY_DONE");
    }

    @Test
    void 웹훅이_없던_변경은_재조회_경로로_한_번만_반영된다() {
        mock.webhook(stack.inboundWebhookUrl(), 0, false, 1, 1);
        mock.createOrder("202611110000002", 5002, "PAY_DONE", true,
                List.of(MockShopby.option(OPTION, "색상", "흰색")));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(stats.count("WEBHOOK", "APPLIED")).isEqualTo(1));

        // 웹훅 없이 상태만 바뀐다 (유실 흉내)
        mock.changeStatus(OPTION, "PRODUCT_PREPARE", false);
        StatusChangeRequeryJob job = stack.bean(App.RECONCILER, StatusChangeRequeryJob.class);
        job.run();
        job.run();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(stats.count("POLLING", "APPLIED")).isEqualTo(1);
            assertThat(stats.count("POLLING", "DUPLICATE")).isEqualTo(1);
        });
        assertThat(historyCount()).isEqualTo(2);
        assertThat(stack.jdbc().queryForObject(
                "select status from order_line_state where order_product_option_no = ?", String.class, OPTION))
                .isEqualTo("PRODUCT_PREPARE");
    }

    private long historyCount() {
        return stack.jdbc().queryForObject(
                "select count(*) from status_history where order_product_option_no = ?", Long.class, OPTION);
    }
}
