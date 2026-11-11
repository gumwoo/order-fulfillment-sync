package io.github.gumwoo.fulfillment.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.gumwoo.fulfillment.core.shipment.AlertBundler;
import io.github.gumwoo.fulfillment.core.shipment.InvoiceSyncService;
import io.github.gumwoo.fulfillment.scenario.support.App;
import io.github.gumwoo.fulfillment.scenario.support.FulfillmentFlow;
import io.github.gumwoo.fulfillment.scenario.support.MockShopby;
import io.github.gumwoo.fulfillment.worker.job.TplEventPollJob;
import io.github.gumwoo.fulfillment.worker.message.ProcessingStats;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * F-12: 출고 확정이 송장보다 먼저 오면 지연 큐로 갔다가 송장 도착 후 반영되고,
 * 최대 횟수 초과 시 conflict와 묶음 알림 1건이 생긴다.
 * 테스트는 지연 간격을 1초, 최대 8회로 줄인다(운영 기본 5분, 6회). 첫 테스트가 송장을 만드는 사이에 포기하지 않을 여유다.
 */
class DelayedRequeueScenarioTest {

    FulfillmentFlow flow;
    MockShopby mock;
    ProcessingStats stats;

    @BeforeEach
    void setUp() {
        flow = FulfillmentFlow.start(Set.of(),
                Map.of("app.worker.release-wait", "PT1S", "app.worker.max-release-waits", "8"));
        mock = flow.mock();
        stats = flow.stack().bean(App.WORKER, ProcessingStats.class);
    }

    @Test
    void 출고확정이_송장보다_먼저_오면_지연_재처리_후_송장_도착_뒤_반영된다() {
        long no = 71_001;
        flow.createPaidOrderAndAwaitRequested(no, 900_001);
        mock.tplAdvance(no, "RELEASED");
        poll();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(stats.count("TPL_EVENT", "DELAYED")).isPositive());
        InvoiceSyncService sync = flow.stack().bean(App.WORKER, InvoiceSyncService.class);
        assertThat(sync.syncPending().sent()).as("송장이 없으니 아직 보낼 것이 없다").isZero();

        mock.tplInvoice(no, "INV-71001");
        poll();
        flow.stack().awaitQueueDrained(Duration.ofSeconds(30));

        assertThat(sync.syncPending()).isEqualTo(new InvoiceSyncService.Result(1, 1, 0));
        assertThat(mock.option(no).path("orderStatusType").asText()).isEqualTo("DELIVERY_ING");
        assertThat(stats.count("TPL_EVENT", "GAVE_UP")).isZero();
        assertThat(flow.stack().jdbc().queryForObject("select count(*) from conflict", Long.class)).isZero();
    }

    @Test
    void 송장이_끝내_오지_않으면_최대_횟수_뒤_conflict와_묶음_알림_1건이_생긴다() {
        List<Long> options = List.of(71_101L, 71_102L, 71_103L);
        flow.createPaidOrdersAndAwaitRequested(options, n -> 910_000 + n);
        options.forEach(no -> mock.tplAdvance(no, "RELEASED"));
        poll();

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(stats.count("TPL_EVENT", "GAVE_UP")).isEqualTo(3));
        assertThat(flow.stack().jdbc().queryForList("select reason_code from conflict", String.class))
                .hasSize(3).containsOnly("RELEASE_WITHOUT_INVOICE");

        AlertBundler bundler = flow.stack().bean(App.WORKER, AlertBundler.class);
        long notificationId = bundler.flush();
        long again = bundler.flush();

        assertThat(notificationId).isPositive();
        assertThat(again).as("새 경보가 없으면 알림도 없다").isEqualTo(-1);
        List<Map<String, Object>> notes = flow.stack().jdbc().queryForList("select alert_count, text from notification");
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0)).containsEntry("alert_count", 3);
        assertThat((String) notes.get(0).get("text")).contains("RELEASE_WITHOUT_INVOICE: 3건");
    }

    private void poll() {
        flow.stack().bean(App.WORKER, TplEventPollJob.class).poll();
    }
}
