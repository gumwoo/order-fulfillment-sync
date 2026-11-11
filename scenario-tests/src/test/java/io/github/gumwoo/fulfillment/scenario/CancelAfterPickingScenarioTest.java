package io.github.gumwoo.fulfillment.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.gumwoo.fulfillment.scenario.support.FulfillmentFlow;
import io.github.gumwoo.fulfillment.scenario.support.MockShopby;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** F-10: 피킹 이후 취소가 들어오면 이미출고 API가 호출되고 CS 회수 목록에 1건이 생긴다. */
class CancelAfterPickingScenarioTest {

    static final long OPTION = 52_001;
    static final long CLAIM = 9_301;

    FulfillmentFlow flow;
    MockShopby mock;

    @BeforeEach
    void setUp() {
        flow = FulfillmentFlow.start(Set.of());
        mock = flow.mock();
        flow.createPaidOrderAndAwaitRequested(OPTION, 620_001);
    }

    @Test
    void 피킹_이후_취소는_이미출고_처리하고_CS_회수_목록에_1건이_생긴다() {
        mock.tplAdvance(OPTION, "PICKED");

        mock.changeClaim(OPTION, "CANCEL_REQUEST", CLAIM, true);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(flow.cancelState(OPTION)).isEqualTo("RECALL_REQUIRED"));
        assertThat(mock.stats().path("claimCalls").path("already-delivery:" + CLAIM).asInt()).isEqualTo(1);
        assertThat(mock.stats().path("claimCalls").path("approve:" + CLAIM).isMissingNode()).isTrue();
        List<Map<String, Object>> recalls = flow.stack().jdbc().queryForList(
                "select order_product_option_no, claim_no, reason from recall_task");
        assertThat(recalls).hasSize(1);
        assertThat(recalls.get(0)).containsEntry("order_product_option_no", OPTION).containsEntry("claim_no", CLAIM)
                .containsEntry("reason", "CANCEL_AFTER_PICKING");
        assertThat(mock.tplOrder(OPTION).path("status").asText()).as("3PL은 그대로 출고 진행").isEqualTo("PICKED");
        // 샵바이: 클레임 철회 + 배송중 → 웹훅으로 우리 상태도 배송중, 클레임 정보 지워짐
        assertThat(mock.option(OPTION).path("orderStatusType").asText()).isEqualTo("DELIVERY_ING");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Map<String, Object> line = flow.stack().jdbc().queryForMap(
                    "select status, claim_status from order_line_state where order_product_option_no = ?", OPTION);
            assertThat(line).containsEntry("status", "DELIVERY_ING");
            assertThat(line.get("claim_status")).isNull();
        });
    }

    @Test
    void 출고완료_후_취소도_송장과_함께_이미출고_처리된다() {
        mock.tplAdvance(OPTION, "RELEASED");
        mock.tplInvoice(OPTION, "INV-52001");

        mock.changeClaim(OPTION, "CANCEL_REQUEST", CLAIM, true);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(flow.cancelState(OPTION)).isEqualTo("RECALL_REQUIRED"));
        assertThat(mock.option(OPTION).path("orderStatusType").asText()).isEqualTo("DELIVERY_ING");
        assertThat(flow.stack().jdbc().queryForObject("select invoice_no from recall_task", String.class))
                .isEqualTo("INV-52001");
        assertThat(flow.stack().jdbc().queryForObject("select count(*) from recall_task", Long.class)).isEqualTo(1);
    }
}
