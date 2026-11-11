package io.github.gumwoo.fulfillment.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.gumwoo.fulfillment.scenario.support.FulfillmentFlow;
import io.github.gumwoo.fulfillment.scenario.support.MockShopby;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** F-08: 3PL 상태가 출고요청일 때 취소가 들어오면 3PL 취소 후 샵바이 취소가 완료된다. */
class CancelBeforeWorkScenarioTest {

    static final long OPTION = 50_001;
    static final long CLAIM = 9_001;

    FulfillmentFlow flow;
    MockShopby mock;

    @BeforeEach
    void setUp() {
        flow = FulfillmentFlow.start(Set.of());
        mock = flow.mock();
    }

    @Test
    void 출고요청_상태에서_취소하면_3PL_취소_후_샵바이_취소가_완료된다() {
        flow.createPaidOrderAndAwaitRequested(OPTION, 600_001);

        mock.changeClaim(OPTION, "CANCEL_REQUEST", CLAIM, true);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(mock.option(OPTION).path("claimStatusType").asText()).isEqualTo("CANCEL_DONE");
            assertThat(flow.cancelState(OPTION)).isEqualTo("DONE");
        });
        assertThat(mock.tplOrder(OPTION).path("status").asText()).isEqualTo("CANCELED");
        assertThat(flow.cancelCase(OPTION)).containsEntry("tpl_status_at_request", "REQUESTED").containsEntry("fee", 0);
        JsonNode stats = mock.stats();
        assertThat(stats.path("holdCalls")).as("출고요청 단계는 배송보류가 필요 없다").isEmpty();
        assertThat(stats.path("claimCalls").path("approve:" + CLAIM).asInt()).isEqualTo(1);
        // 샵바이 승인 결과(CANCEL_DONE)가 웹훅으로 돌아와 우리 상태에도 반영된다
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(flow.stack().jdbc().queryForObject(
                        "select claim_status from order_line_state where order_product_option_no = ?", String.class, OPTION))
                        .isEqualTo("CANCEL_DONE"));
    }

    @Test
    void 같은_취소_웹훅이_여러_번_와도_3PL_취소와_승인은_한_번이다() {
        flow.createPaidOrderAndAwaitRequested(OPTION, 600_001);
        mock.webhook(flow.stack().inboundWebhookUrl(), 0, false, 3, 1);

        mock.changeClaim(OPTION, "CANCEL_REQUEST", CLAIM, true);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(flow.cancelState(OPTION)).isEqualTo("DONE"));
        flow.stack().awaitQueueDrained(Duration.ofSeconds(30));
        assertThat(mock.tplStats().path("cancelCalls").path(String.valueOf(OPTION)).asInt()).isEqualTo(1);
        assertThat(mock.stats().path("claimCalls").path("approve:" + CLAIM).asInt()).isEqualTo(1);
    }
}
