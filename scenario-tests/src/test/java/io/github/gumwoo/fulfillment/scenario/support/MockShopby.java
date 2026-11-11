package io.github.gumwoo.fulfillment.scenario.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/** mock 샵바이 제어 API를 부르는 테스트 도우미 */
public class MockShopby {

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final URI base;

    public MockShopby(int port) {
        this.base = URI.create("http://localhost:" + port);
    }

    /** 샵바이와 3PL mock 모두 초기화 */
    public void reset() {
        post("/_mock/shopby/reset", Map.of());
        post("/_mock/tpl/reset", Map.of());
    }

    /** 다음 N번 호출에 429, 다음 N번 응답에 EXHAUSTED 헤더, 초당 한도(0이면 끔) */
    public void rateLimitFaults(int next429, int nextExhausted, int limitPerSecond) {
        post("/_mock/shopby/faults/rate-limit", Map.of("next429", next429, "nextExhausted", nextExhausted,
                "limitPerSecond", limitPerSecond));
    }

    // ---- 3PL mock ----

    public void tplMapping(boolean enforce, List<String> allowed) {
        post("/_mock/tpl/mapping", Map.of("enforce", enforce, "allowed", allowed));
    }

    public void tplAllowMapping(String optionNameAndValue) {
        post("/_mock/tpl/mapping/allow", Map.of("key", optionNameAndValue));
    }

    public void tplAdvance(long optionNo, String status) {
        post("/_mock/tpl/orders/" + optionNo + "/advance", Map.of("status", status));
    }

    public void tplInvoice(long optionNo, String invoiceNo) {
        post("/_mock/tpl/orders/" + optionNo + "/invoice", Map.of("invoiceNo", invoiceNo, "deliveryCompanyType", "CJ"));
    }

    public JsonNode tplOrder(long optionNo) {
        return get("/tpl/orders/" + optionNo);
    }

    public JsonNode tplStats() {
        return get("/_mock/tpl/stats");
    }

    /** 웹훅 수신 URL과 장애 주입 설정 */
    public void webhook(String url, double dropRatio, boolean recordDroppedAsFailed, int duplicates, long seed) {
        post("/_mock/shopby/webhook-config", Map.of("url", url, "dropRatio", dropRatio,
                "recordDroppedAsFailed", recordDroppedAsFailed, "duplicates", duplicates, "seed", seed));
    }

    /** 주문 하나에 옵션 여러 개 */
    public void createOrder(String orderNo, long deliveryNo, String status, boolean sendWebhook, List<Map<String, Object>> options) {
        post("/_mock/shopby/orders", Map.of("orderNo", orderNo, "deliveryNo", deliveryNo, "orderStatusType", status,
                "sendWebhook", sendWebhook, "options", options));
    }

    public static Map<String, Object> option(long optionNo, String optionName, String optionValue) {
        return Map.of("orderProductOptionNo", optionNo, "productName", "티셔츠", "optionName", optionName,
                "optionValue", optionValue, "orderCnt", 1);
    }

    public void changeStatus(long optionNo, String status, boolean sendWebhook) {
        post("/_mock/shopby/options/" + optionNo + "/status", Map.of("orderStatusType", status, "sendWebhook", sendWebhook));
    }

    public void changeClaim(long optionNo, String claimStatus, Long claimNo, boolean sendWebhook) {
        java.util.HashMap<String, Object> body = new java.util.HashMap<>();
        body.put("claimStatusType", claimStatus);
        body.put("claimNo", claimNo);
        body.put("sendWebhook", sendWebhook);
        post("/_mock/shopby/options/" + optionNo + "/claim", body);
    }

    public JsonNode option(long optionNo) {
        return get("/_mock/shopby/options/" + optionNo);
    }

    public JsonNode options() {
        return get("/_mock/shopby/options");
    }

    public JsonNode stats() {
        return get("/_mock/shopby/stats");
    }

    public void addFailedWebhook(String exceptionDateTime) {
        post("/_mock/shopby/failed-webhooks", Map.of("eventType", "CHANGE_ORDER_STATUS",
                "exceptionDateTime", exceptionDateTime, "data", "data"));
    }

    public void failHoldOnce(List<Long> optionNos) {
        post("/_mock/shopby/faults/hold-fail-once", optionNos);
    }

    public void failInvoiceOnce(List<Long> shippingNos) {
        post("/_mock/shopby/faults/invoice-fail-once", shippingNos);
    }

    public JsonNode get(String path) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return mapper.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void post(String path, Object body) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(base.resolve(path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 300) {
                throw new IllegalStateException("mock " + path + " -> " + r.statusCode() + " " + r.body());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
