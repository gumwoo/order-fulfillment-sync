package io.github.gumwoo.fulfillment.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.gumwoo.fulfillment.core.shipment.InvoiceSyncService;
import io.github.gumwoo.fulfillment.scenario.support.App;
import io.github.gumwoo.fulfillment.scenario.support.FulfillmentFlow;
import io.github.gumwoo.fulfillment.scenario.support.MockShopby;
import io.github.gumwoo.fulfillment.worker.job.TplEventPollJob;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * F-11 보강(리뷰 지적): 송장 반영은 배송번호 단위다. 한 배송에 옵션이 여러 개면 모두 출고완료·송장이 있을 때만 보내고,
 * 옵션별 송장이 다르면 보내지 않고 conflict로 남긴다. 3PL 이벤트가 순서 없이 처리돼도 상태는 되돌아가지 않는다.
 */
class MultiOptionDeliveryInvoiceScenarioTest {

    static final long A = 73_001;
    static final long B = 73_002;
    static final long DELIVERY = 940_001;

    FulfillmentFlow flow;
    MockShopby mock;
    InvoiceSyncService sync;

    @BeforeEach
    void setUp() {
        flow = FulfillmentFlow.start(Set.of(), java.util.Map.of("app.worker.release-wait", "PT1S"));
        mock = flow.mock();
        mock.createOrder("2026111173", DELIVERY, "PAY_DONE", true,
                List.of(MockShopby.option(A, "색상", "검정"), MockShopby.option(B, "색상", "흰색")));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(mock.tplOrder(A).path("status").asText()).isEqualTo("REQUESTED");
            assertThat(mock.tplOrder(B).path("status").asText()).isEqualTo("REQUESTED");
        });
        sync = flow.stack().bean(App.WORKER, InvoiceSyncService.class);
    }

    @Test
    void 한_배송의_옵션이_모두_출고완료되어야_송장을_한_번_보낸다() {
        mock.tplAdvance(A, "RELEASED");
        mock.tplInvoice(A, "INV-73");
        pollAndAwait(A);

        assertThat(sync.syncPending().sent()).as("B가 아직 출고 전이라 보내지 않는다").isZero();

        mock.tplAdvance(B, "WORKING");
        mock.tplAdvance(B, "PICKED");
        mock.tplAdvance(B, "RELEASED");
        mock.tplInvoice(B, "INV-73");
        pollAndAwait(B);

        assertThat(sync.syncPending()).isEqualTo(new InvoiceSyncService.Result(1, 1, 0));
        assertThat(sync.syncPending().sent()).isZero();
        assertThat(mock.stats().path("invoiceRequests")).hasSize(1);
        assertThat(mock.option(A).path("orderStatusType").asText()).isEqualTo("DELIVERY_ING");
        assertThat(mock.option(B).path("orderStatusType").asText()).isEqualTo("DELIVERY_ING");
        // B의 WORKING·PICKED·RELEASED 이벤트가 순서 없이 처리돼도 출고 행은 RELEASED에 머문다
        assertThat(flow.stack().jdbc().queryForObject(
                "select tpl_status from shipment where order_product_option_no = ?", String.class, B)).isEqualTo("RELEASED");
    }

    @Test
    void 한_배송의_옵션별_송장이_다르면_보내지_않고_conflict를_남긴다() {
        mock.tplAdvance(A, "RELEASED");
        mock.tplInvoice(A, "INV-A");
        mock.tplAdvance(B, "RELEASED");
        mock.tplInvoice(B, "INV-B");
        pollAndAwait(A);
        pollAndAwait(B);

        assertThat(sync.syncPending().sent()).isZero();
        assertThat(sync.syncPending().sent()).isZero();
        assertThat(mock.stats().path("invoiceRequests")).isEmpty();
        assertThat(flow.stack().jdbc().queryForList("select reason_code from conflict", String.class))
                .containsExactly("INVOICE_MISMATCH_IN_DELIVERY");
    }

    private void pollAndAwait(long optionNo) {
        flow.stack().bean(App.WORKER, TplEventPollJob.class).poll();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(flow.stack().jdbc().queryForObject(
                        "select count(*) from shipment where order_product_option_no = ? and tpl_status = 'RELEASED'"
                                + " and invoice_no is not null", Long.class, optionNo)).isEqualTo(1));
    }
}
