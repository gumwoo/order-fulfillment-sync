package io.github.gumwoo.fulfillment.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gumwoo.fulfillment.scenario.support.App;
import io.github.gumwoo.fulfillment.scenario.support.FulfillmentFlow;
import io.github.gumwoo.fulfillment.scenario.support.MockShopby;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * F-13: 옵션명 매핑 실패가 미연동 목록에 사유 코드와 함께 보이고, 재처리를 두 번 눌러도 한 번만 처리되며 이력이 남는다.
 */
class AdminReprocessScenarioTest {

    static final long OPTION = 72_001;

    FulfillmentFlow flow;
    MockShopby mock;
    HttpClient http = HttpClient.newHttpClient();
    ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        flow = FulfillmentFlow.start(Set.of(App.ADMIN));
        mock = flow.mock();
        // 3PL 매핑표에는 "색상|검정"만 있다. 쇼핑몰에서 옵션값을 "빨강"으로 바꾼 상황 (파스토 미연동 사례)
        mock.tplMapping(true, List.of("색상|검정"));
        mock.createOrder("20261111" + OPTION, 930_001, "PAY_DONE", true, List.of(MockShopby.option(OPTION, "색상", "빨강")));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(flow.stack().jdbc().queryForObject(
                        "select count(*) from unlinked where order_product_option_no = ?", Long.class, OPTION)).isEqualTo(1));
    }

    @Test
    void 미연동_목록에_사유코드와_함께_보이고_재처리는_두_번_눌러도_한_번만_처리되며_이력이_남는다() throws Exception {
        JsonNode open = get("/admin/unlinked?status=OPEN");
        assertThat(open).hasSize(1);
        JsonNode row = open.get(0);
        assertThat(row.path("orderProductOptionNo").asLong()).isEqualTo(OPTION);
        assertThat(row.path("reasonCode").asText()).isEqualTo("OPTION_NOT_MAPPED");
        assertThat(row.path("optionValue").asText()).isEqualTo("빨강");
        assertThat(mock.option(OPTION).path("orderStatusType").asText()).as("3PL에 못 넘겼으니 상품준비중도 아니다")
                .isEqualTo("PAY_DONE");
        long unlinkedId = row.path("id").asLong();

        // CS가 3PL 매핑표를 고친 뒤 재처리 버튼을 (실수로) 동시에 두 번 누른다
        mock.tplAllowMapping("색상|빨강");
        CompletableFuture<HttpResponse<String>> first = reprocessAsync(unlinkedId, "req-1", "cs-kim");
        CompletableFuture<HttpResponse<String>> second = reprocessAsync(unlinkedId, "req-1", "cs-kim");
        List<JsonNode> results = List.of(json(first.get()), json(second.get()));

        assertThat(results).filteredOn(r -> !r.path("duplicateRequest").asBoolean()).singleElement()
                .satisfies(r -> assertThat(r.path("result").asText()).isEqualTo("RESOLVED"));
        assertThat(results).filteredOn(r -> r.path("duplicateRequest").asBoolean()).hasSize(1);

        // 다른 요청 ID로 한 번 더 눌러도 이미 해결된 건은 다시 처리하지 않는다
        JsonNode third = json(reprocessAsync(unlinkedId, "req-2", "cs-lee").get());
        assertThat(third.path("result").asText()).isEqualTo("ALREADY_RESOLVED");

        assertThat(mock.tplStats().path("registerCalls").path(String.valueOf(OPTION)).asInt())
                .as("처음 거절 1회 + 재처리 1회").isEqualTo(2);
        assertThat(mock.option(OPTION).path("orderStatusType").asText()).isEqualTo("PRODUCT_PREPARE");
        assertThat(get("/admin/unlinked?status=OPEN")).isEmpty();
        JsonNode resolved = get("/admin/unlinked?status=RESOLVED");
        assertThat(resolved.get(0).path("resolver").asText()).isEqualTo("cs-kim");

        JsonNode history = get("/admin/history?targetKey=unlinked:" + unlinkedId);
        assertThat(history).hasSize(2);
        assertThat(history).anySatisfy(h -> {
            assertThat(h.path("requestId").asText()).isEqualTo("req-1");
            assertThat(h.path("actor").asText()).isEqualTo("cs-kim");
            assertThat(h.path("result").asText()).isEqualTo("RESOLVED");
        });
        assertThat(history).anySatisfy(h -> {
            assertThat(h.path("requestId").asText()).isEqualTo("req-2");
            assertThat(h.path("result").asText()).isEqualTo("ALREADY_RESOLVED");
        });
    }

    @Test
    void 매핑을_고치기_전_재처리는_미연동으로_남고_이력에_남는다() throws Exception {
        long unlinkedId = get("/admin/unlinked?status=OPEN").get(0).path("id").asLong();

        JsonNode r = json(reprocessAsync(unlinkedId, "req-x", "cs-kim").get());

        assertThat(r.path("result").asText()).isEqualTo("STILL_UNLINKED");
        assertThat(get("/admin/unlinked?status=OPEN")).hasSize(1);
        assertThat(flow.stack().jdbc().queryForObject("select count(*) from unlinked", Long.class))
                .as("미해결 건은 옵션당 하나").isEqualTo(1);
        assertThat(get("/admin/history?targetKey=unlinked:" + unlinkedId)).hasSize(1);
    }

    @Test
    void 다른_요청_ID로_동시에_눌러도_실제_처리는_한_번이다() throws Exception {
        long unlinkedId = get("/admin/unlinked?status=OPEN").get(0).path("id").asLong();
        mock.tplAllowMapping("색상|빨강");

        CompletableFuture<HttpResponse<String>> a = reprocessAsync(unlinkedId, "req-a", "cs-kim");
        CompletableFuture<HttpResponse<String>> b = reprocessAsync(unlinkedId, "req-b", "cs-lee");
        List<JsonNode> results = List.of(json(a.get()), json(b.get()));

        assertThat(results).filteredOn(r -> r.path("result").asText().equals("RESOLVED")).hasSize(1);
        assertThat(results).filteredOn(r -> !r.path("result").asText().equals("RESOLVED"))
                .singleElement()
                .satisfies(r -> assertThat(r.path("result").asText()).isIn("IN_PROGRESS_BY_OTHER", "ALREADY_RESOLVED"));
        assertThat(mock.tplStats().path("registerCalls").path(String.valueOf(OPTION)).asInt()).isEqualTo(2);
        assertThat(get("/admin/history?targetKey=unlinked:" + unlinkedId)).hasSize(2);
    }

    @Test
    void 같은_요청_ID를_다른_대상에_쓰면_409다() throws Exception {
        long unlinkedId = get("/admin/unlinked?status=OPEN").get(0).path("id").asLong();
        json(reprocessAsync(unlinkedId, "req-z", "cs-kim").get());

        HttpResponse<String> reused = reprocessAsync(unlinkedId + 999, "req-z", "cs-kim").get();

        assertThat(reused.statusCode()).isEqualTo(409);
    }

    private CompletableFuture<HttpResponse<String>> reprocessAsync(long id, String requestId, String actor) {
        URI uri = flow.stack().baseUrl(App.ADMIN).resolve("/admin/unlinked/" + id + "/reprocess");
        return http.sendAsync(HttpRequest.newBuilder(uri).header("Idempotency-Key", requestId).header("X-Actor", actor)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(flow.stack().baseUrl(App.ADMIN).resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        return mapper.readTree(r.body());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return mapper.readTree(r.body());
    }

}
