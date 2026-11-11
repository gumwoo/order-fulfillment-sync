package io.github.gumwoo.fulfillment.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.gumwoo.fulfillment.core.cancel.CancelService;
import io.github.gumwoo.fulfillment.scenario.support.App;
import io.github.gumwoo.fulfillment.scenario.support.FulfillmentFlow;
import io.github.gumwoo.fulfillment.scenario.support.MockShopby;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * F-09: 출고작업(피킹 전)에 취소가 들어오면 샵바이 배송보류가 걸리고, 3PL 취소 성공 후 취소가 완료된다.
 * 배송보류 일괄 실패 시 건별 재시도한다.
 */
class CancelDuringWorkScenarioTest {

    static final List<Long> OPTIONS = List.of(51_001L, 51_002L, 51_003L);

    FulfillmentFlow flow;
    MockShopby mock;

    @BeforeEach
    void setUp() {
        flow = FulfillmentFlow.start(Set.of());
        mock = flow.mock();
        for (long no : OPTIONS) {
            flow.createPaidOrderAndAwaitRequested(no, 610_000 + no);
            mock.tplAdvance(no, "WORKING");
        }
    }

    @Test
    void 출고작업_중_취소는_배송보류_후_3PL_취소_뒤_완료되고_일괄_실패면_건별로_재시도한다() {
        // 일괄 배송보류에 51002가 들어 있으면 한 번 실패 → 하나라도 실패하면 전부 미반영 → 건별 재시도
        mock.failHoldOnce(List.of(51_002L));
        for (long no : OPTIONS) {
            mock.changeClaim(no, "CANCEL_REQUEST", 9_100 + no, true);
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                OPTIONS.forEach(no -> assertThat(flow.cancelState(no)).isEqualTo("HOLD_PENDING")));
        // 아직 3PL 취소 전이다
        OPTIONS.forEach(no -> assertThat(mock.tplOrder(no).path("status").asText()).isEqualTo("WORKING"));

        CancelService service = flow.stack().bean(App.WORKER, CancelService.class);
        CancelService.HoldResult r = service.holdPending();

        assertThat(r.bulkFailed()).isTrue();
        assertThat(r.held()).isEqualTo(3);
        assertThat(mock.stats().path("holdCalls").toString()).as("일괄 3건 실패 후 건별 1건씩").isEqualTo("[3,1,1,1]");
        // 배송보류가 실제로 걸렸고, 3PL 취소는 아직이다
        OPTIONS.forEach(no -> {
            assertThat(mock.option(no).path("holdDeliveryYn").asText()).isEqualTo("Y");
            assertThat(mock.tplOrder(no).path("status").asText()).isEqualTo("WORKING");
            assertThat(flow.cancelState(no)).isEqualTo("HELD");
        });

        service.resume();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> OPTIONS.forEach(no -> {
            assertThat(flow.cancelState(no)).isEqualTo("DONE");
            assertThat(mock.option(no).path("claimStatusType").asText()).isEqualTo("CANCEL_DONE");
        }));
        OPTIONS.forEach(no -> {
            assertThat(mock.tplOrder(no).path("status").asText()).isEqualTo("CANCELED");
            assertThat(flow.cancelCase(no)).containsEntry("tpl_status_at_request", "WORKING").containsEntry("fee", 1000);
        });
    }

    @Test
    void 건별로도_배송보류가_안_되는_건은_HOLD_FAILED와_conflict로_남고_나머지는_완료된다() {
        // 51003은 샵바이에서 이미 배송중이라 배송보류 대상이 아니다(PRODUCT_PREPARE, DELIVERY_PREPARE만 가능)
        mock.changeStatus(51_003L, "DELIVERY_ING", false);
        for (long no : OPTIONS) {
            mock.changeClaim(no, "CANCEL_REQUEST", 9_200 + no, true);
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                OPTIONS.forEach(no -> assertThat(flow.cancelState(no)).isEqualTo("HOLD_PENDING")));

        CancelService service = flow.stack().bean(App.WORKER, CancelService.class);
        CancelService.HoldResult r = service.holdPending();
        service.resume();

        assertThat(r.bulkFailed()).isTrue();
        assertThat(r.held()).isEqualTo(2);
        assertThat(r.failed()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(flow.cancelState(51_001L)).isEqualTo("DONE");
            assertThat(flow.cancelState(51_002L)).isEqualTo("DONE");
        });
        assertThat(flow.cancelState(51_003L)).isEqualTo("HOLD_FAILED");
        assertThat(mock.tplOrder(51_003L).path("status").asText()).as("보류하지 못했으니 3PL 취소도 하지 않는다").isEqualTo("WORKING");
        List<String> reasons = new ArrayList<>(flow.stack().jdbc().queryForList(
                "select reason_code from conflict where target_key = '51003'", String.class));
        assertThat(reasons).containsExactly("HOLD_DELIVERY_FAILED");
    }
}
