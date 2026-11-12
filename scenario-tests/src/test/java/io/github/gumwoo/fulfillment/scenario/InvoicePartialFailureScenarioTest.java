package io.github.gumwoo.fulfillment.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.gumwoo.fulfillment.core.shipment.InvoiceSyncService;
import io.github.gumwoo.fulfillment.scenario.support.App;
import io.github.gumwoo.fulfillment.scenario.support.FulfillmentFlow;
import io.github.gumwoo.fulfillment.scenario.support.MockShopby;
import io.github.gumwoo.fulfillment.worker.job.TplEventPollJob;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** F-11: 송장 100건 일괄 반영 중 7건이 실패하면 그 7건만 다시 보낸다. */
class InvoicePartialFailureScenarioTest {

    static final List<Long> OPTIONS = LongStream.range(70_001, 70_101).boxed().toList();

    FulfillmentFlow flow;
    MockShopby mock;

    static long shippingNo(long optionNo) {
        return 800_000 + optionNo;
    }

    @BeforeEach
    void setUp() {
        flow = FulfillmentFlow.start(Set.of());
        mock = flow.mock();
        flow.createPaidOrdersAndAwaitRequested(OPTIONS, InvoicePartialFailureScenarioTest::shippingNo);
        for (long no : OPTIONS) {
            mock.tplAdvance(no, "RELEASED");
            mock.tplInvoice(no, "INV-" + no);
        }
        flow.stack().bean(App.WORKER, TplEventPollJob.class).poll();
        // 출고완료 이벤트가 송장 이벤트보다 먼저 처리되면 지연 재처리(F-12)로 큐에 남는다. 상태와 송장은 이미 저장되므로
        // 큐가 비기를 기다리지 않고 DB에 다 들어왔는지를 기다린다
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() ->
                assertThat(flow.stack().jdbc().queryForObject(
                        "select count(*) from shipment where tpl_status = 'RELEASED' and invoice_no is not null", Long.class))
                        .isEqualTo((long) OPTIONS.size()));
    }

    @Test
    void 송장_100건_중_7건이_실패하면_그_7건만_다시_보낸다() {
        List<Long> failing = List.of(3L, 11L, 27L, 42L, 58L, 77L, 96L).stream()
                .map(i -> shippingNo(70_000 + i)).toList();
        mock.failInvoiceOnce(failing);
        InvoiceSyncService sync = flow.stack().bean(App.WORKER, InvoiceSyncService.class);

        InvoiceSyncService.Result first = sync.syncPending();
        InvoiceSyncService.Result second = sync.syncPending();
        InvoiceSyncService.Result third = sync.syncPending();

        assertThat(first).isEqualTo(new InvoiceSyncService.Result(100, 93, 7));
        assertThat(second).isEqualTo(new InvoiceSyncService.Result(7, 7, 0));
        assertThat(third.sent()).isZero();
        JsonNode requests = mock.stats().path("invoiceRequests");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0)).hasSize(100);
        assertThat(toSet(requests.get(1))).as("두 번째 요청은 실패한 7건만").isEqualTo(new HashSet<>(failing));
        for (long no : OPTIONS) {
            JsonNode o = mock.option(no);
            assertThat(o.path("orderStatusType").asText()).isEqualTo("DELIVERY_ING");
        }
        // 샵바이 웹훅으로 우리 라인도 배송중·송장 반영
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(flow.stack().jdbc().queryForObject(
                        "select count(*) from order_line_state where status = 'DELIVERY_ING' and invoice_no is not null",
                        Long.class)).isEqualTo(100L));
    }

    @Test
    void 반영됐는데_응답을_잃으면_다시_보낼_때_거절돼도_반영_완료로_끝낸다() {
        mock.loseInvoiceResponseOnce();
        InvoiceSyncService sync = flow.stack().bean(App.WORKER, InvoiceSyncService.class);

        assertThatThrownBy(sync::syncPending).as("응답을 읽지 못해 실패로 보이고 트랜잭션이 되돌아간다")
                .isInstanceOf(RuntimeException.class);
        for (long no : OPTIONS) {
            assertThat(mock.option(no).path("orderStatusType").asText()).as("샵바이에는 반영됐다").isEqualTo("DELIVERY_ING");
        }
        // 샵바이 웹훅으로 우리 라인이 배송중·송장을 받는다
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(flow.stack().jdbc().queryForObject(
                        "select count(*) from order_line_state where status = 'DELIVERY_ING' and invoice_no is not null",
                        Long.class)).isEqualTo(100L));

        InvoiceSyncService.Result retry = sync.syncPending();

        assertThat(retry).as("다시 보낸 100건은 모두 '이미 배송중'으로 거절되지만 반영 완료로 본다")
                .isEqualTo(new InvoiceSyncService.Result(100, 100, 0));
        assertThat(sync.syncPending().sent()).isZero();
        assertThat(flow.stack().jdbc().queryForObject(
                "select count(*) from conflict where reason_code = 'INVOICE_SYNC_FAILED'", Long.class)).isZero();
    }

    private static Set<Long> toSet(JsonNode arr) {
        List<Long> l = new ArrayList<>();
        arr.forEach(n -> l.add(n.asLong()));
        return new HashSet<>(l);
    }
}
