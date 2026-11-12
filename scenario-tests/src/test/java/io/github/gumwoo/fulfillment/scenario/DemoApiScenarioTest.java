package io.github.gumwoo.fulfillment.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gumwoo.fulfillment.scenario.support.App;
import io.github.gumwoo.fulfillment.scenario.support.ScenarioStack;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * F-16(백엔드): 데모 대시보드가 부르는 API로 주문 생성과 장애를 넣으면, 스케줄된 복구 경로만으로 불일치가 0이 되고
 * 현황 API가 그 과정을 보여 준다. 화면 버튼이 하는 일을 그대로 API로 한다(사람 개입·작업 직접 호출 없음).
 */
class DemoApiScenarioTest {

    static final int ORDERS = 40;

    ScenarioStack stack = ScenarioStack.get();
    HttpClient http = HttpClient.newHttpClient();
    ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        Map<App, Map<String, Object>> apps = new LinkedHashMap<>();
        apps.put(App.MOCK, Map.of());
        apps.put(App.INBOUND, Map.of());
        apps.put(App.WORKER, Map.of("app.worker.fulfillment-enabled", "true", "app.worker.scheduling-enabled", "true",
                "app.worker.tpl-poll-interval", "PT2S", "app.worker.invoice-sync-interval", "PT2S",
                "app.worker.hold-interval", "PT2S", "app.worker.release-wait", "PT3S"));
        apps.put(App.RECONCILER, Map.of("app.reconciler.scheduling-enabled", "true",
                "app.reconciler.requery-interval", "PT5S", "app.reconciler.failed-webhook-interval", "PT5S",
                "app.reconciler.reconcile-cron", "*/3 * * * * *", "app.reconciler.failed-webhook-window-before", "PT1M"));
        apps.put(App.ADMIN, Map.of("app.admin.demo-enabled", "true"));
        stack.use(apps);
        post(adminUrl("/admin/demo/reset"), "{}");
        post(mockUrl("/_mock/demo/reset"), "{}");
    }

    @Test
    void 데모_API로_장애를_넣어도_스케줄된_복구만으로_불일치가_0이_되고_현황에_보인다() throws Exception {
        // 쇼핑몰 API 429·대기 한도 초과를 먼저 걸어 두고 주문을 넣는다 (화면의 "429 ×30" 버튼과 같은 호출)
        post(mockUrl("/_mock/demo/faults"), "{\"next429\":20,\"nextExhausted\":10}");
        JsonNode created = post(mockUrl("/_mock/demo/orders"), mapper.writeValueAsString(Map.of(
                "count", ORDERS, "dropRatio", 0.3, "recordDroppedAsFailed", true, "duplicates", 2,
                "webhookUrl", stack.inboundWebhookUrl())));
        assertThat(created.path("created").asInt()).isEqualTo(ORDERS);
        assertThat(created.path("webhooksDropped").asLong()).isPositive();

        // 결제완료 → 3PL 출고요청 → 상품준비중, 유실분은 스케줄된 복구 경로가 메운다
        await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            JsonNode o = get(adminUrl("/admin/overview"));
            assertThat(o.path("orderLines").path("PRODUCT_PREPARE").asLong()).isEqualTo(ORDERS);
            assertThat(o.path("lastReconciliation").path("mismatches").asInt(-1)).isZero();
        });
        // 중복 웹훅·재조회가 같은 옵션을 동시에 처리해도 상품준비중 변경은 한 번만 한다 (ADR-0010)
        assertThat(get(adminUrl("/admin/overview")).path("openConflicts").has("PREPARE_PRODUCT_REJECTED"))
                .as("동시 처리로 생긴 가짜 상품준비중 거절").isFalse();
        JsonNode runs = get(adminUrl("/admin/reconciliation/runs?limit=200"));
        assertThat(StreamSupport.stream(runs.spliterator(), false).anyMatch(r -> r.path("mismatches").asInt() > 0))
                .as("유실로 생긴 불일치가 대사 이력에 남았다가 0이 됐다").isTrue();

        // 창고 출고완료(송장 발급) → 송장 반영 → 배송중
        post(mockUrl("/_mock/demo/tpl/advance"), "{\"from\":\"REQUESTED\",\"to\":\"RELEASED\"}");
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            JsonNode o = get(adminUrl("/admin/overview"));
            assertThat(o.path("orderLines").path("DELIVERY_ING").asLong()).isEqualTo(ORDERS);
            assertThat(o.path("invoicePending").asLong()).isZero();
        });

        // 출고 후 고객 취소 3건 → 이미출고 처리, CS 회수 목록
        post(mockUrl("/_mock/demo/cancel"), "{\"count\":3,\"tplStatus\":\"RELEASED\"}");
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(get(adminUrl("/admin/overview")).path("openRecalls").asLong()).isEqualTo(3));

        JsonNode o = get(adminUrl("/admin/overview"));
        assertThat(o.path("dlq").path("visible").asInt()).isZero();
        assertThat(o.path("processedEvents").path("WEBHOOK").asLong()).isPositive();
        assertThat(o.path("queue").has("inFlight")).isTrue();
        JsonNode mockState = get(mockUrl("/_mock/demo/state"));
        assertThat(mockState.path("webhooks").path("dropped").asLong()).isPositive();
        assertThat(mockState.path("shopbyApi").path("rejected429").asLong()).as("429를 실제로 돌려줬다").isPositive();
    }

    @Test
    void 데모_초기화는_데모_설정에서만_된다() throws Exception {
        stack.use(Map.of(App.ADMIN, Map.of()));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(adminUrl("/admin/demo/reset"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(403);
    }

    private URI adminUrl(String path) {
        return stack.baseUrl(App.ADMIN).resolve(path);
    }

    private URI mockUrl(String path) {
        return URI.create("http://localhost:" + stack.mockPort() + path);
    }

    private JsonNode get(URI uri) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return mapper.readTree(r.body());
    }

    private JsonNode post(URI uri, String body) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).as(uri + " " + r.body()).isLessThan(300);
            return r.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
