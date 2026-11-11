package io.github.gumwoo.fulfillment.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.gumwoo.fulfillment.scenario.support.App;
import io.github.gumwoo.fulfillment.scenario.support.ScenarioStack;
import io.github.gumwoo.fulfillment.worker.message.ProcessingStats;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * F-04: DELIVERY_ING 이후에 PAY_DONE이 도착해도 상태가 되돌아가지 않고 conflict가 남는다.
 */
class OutOfOrderScenarioTest {

    static final long OPTION = 8001;

    ScenarioStack stack = ScenarioStack.get();
    HttpClient http = HttpClient.newHttpClient();
    ProcessingStats stats;

    @BeforeEach
    void setUp() {
        stack.use(Set.of(App.INBOUND, App.WORKER));
        stack.reset();
        stats = stack.bean(App.WORKER, ProcessingStats.class);
        stats.reset();
    }

    @Test
    void 배송중_다음에_결제완료가_와도_되돌아가지_않고_conflict가_남는다() throws Exception {
        // 실제 발생 순서: PAY_DONE(10:00) → DELIVERY_ING(12:00). 도착 순서는 거꾸로.
        post(element("DELIVERY_ING", "2026-11-11 10:00:00", "2026-11-11 12:00:00"));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(stats.count("WEBHOOK", "APPLIED")).isEqualTo(1));

        post(element("PAY_DONE", "2026-11-11 10:00:00", null));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(stats.count("WEBHOOK", "CONFLICT")).isEqualTo(1));

        assertThat(status()).isEqualTo("DELIVERY_ING");
        List<Map<String, Object>> conflicts = stack.jdbc().queryForList(
                "select target_key, reason_code, detail from conflict");
        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0)).containsEntry("target_key", String.valueOf(OPTION))
                .containsEntry("reason_code", "BACKWARD_TRANSITION");
        assertThat((String) conflicts.get(0).get("detail")).contains("DELIVERY_ING -> PAY_DONE");
        assertThat(historyCount()).isEqualTo(1);
    }

    @Test
    void 늦게_온_결제완료가_다시_와도_conflict는_하나다() throws Exception {
        post(element("DELIVERY_ING", "2026-11-11 10:00:00", "2026-11-11 12:00:00"));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(stats.count("WEBHOOK", "APPLIED")).isEqualTo(1));

        post(element("PAY_DONE", "2026-11-11 10:00:00", null));
        post(element("PAY_DONE", "2026-11-11 10:00:00", null));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(stats.count("WEBHOOK", "CONFLICT")).isEqualTo(1);
            assertThat(stats.count("WEBHOOK", "DUPLICATE")).isEqualTo(1);
        });
        assertThat(status()).isEqualTo("DELIVERY_ING");
        assertThat(stack.jdbc().queryForObject("select count(*) from conflict", Long.class)).isEqualTo(1);
    }

    @Test
    void 정상_순서면_conflict_없이_두_번_반영된다() throws Exception {
        post(element("PAY_DONE", "2026-11-11 10:00:00", null));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(stats.count("WEBHOOK", "APPLIED")).isEqualTo(1));
        post(element("DELIVERY_ING", "2026-11-11 10:00:00", "2026-11-11 12:00:00"));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(stats.count("WEBHOOK", "APPLIED")).isEqualTo(2));

        assertThat(status()).isEqualTo("DELIVERY_ING");
        assertThat(stack.jdbc().queryForObject("select count(*) from conflict", Long.class)).isZero();
        assertThat(historyCount()).isEqualTo(2);
    }

    private String status() {
        return stack.jdbc().queryForObject(
                "select status from order_line_state where order_product_option_no = ?", String.class, OPTION);
    }

    private long historyCount() {
        return stack.jdbc().queryForObject(
                "select count(*) from status_history where order_product_option_no = ?", Long.class, OPTION);
    }

    private void post(String elementJson) throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(stack.baseUrl(App.INBOUND).resolve("/webhooks/shopby"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("[" + elementJson + "]")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);
    }

    private static String element(String status, String payYmdt, String releaseYmdt) {
        return "{\"eventType\":\"CHANGE_ORDER_STATUS\",\"orderProductOptionNo\":" + OPTION
                + ",\"orderNo\":\"202611110000008\",\"deliveryNo\":5008,\"orderStatusType\":\"" + status
                + "\",\"claimStatusType\":null,\"claimNo\":null,\"orderYmdt\":\"2026-11-11 09:59:00\",\"payYmdt\":\""
                + payYmdt + "\",\"releaseYmdt\":" + (releaseYmdt == null ? "null" : "\"" + releaseYmdt + "\"") + "}";
    }
}
