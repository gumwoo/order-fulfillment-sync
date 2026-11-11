package io.github.gumwoo.fulfillment.infra.shopby;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.gumwoo.fulfillment.core.order.OrderStatusChange;
import io.github.gumwoo.fulfillment.core.port.ShopbyClient;
import io.github.gumwoo.fulfillment.infra.json.OrderStatusChangeJson;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 샵바이 서버 API 어댑터. 모든 호출은 전역 레이트 리미터를 먼저 거친다 (ADR-0007).
 * 응답 헤더 {@code ratelimit-available-level: EXHAUSTED}나 429를 받으면 리미터에 알려 모든 인스턴스가 잠시 멈춘다.
 */
public class RestClientShopbyClient implements ShopbyClient {

    static final String RATE_LIMIT_HEADER = "ratelimit-available-level";
    static final String EXHAUSTED = "EXHAUSTED";

    private final RestClient http;
    private final RedisTokenBucketRateLimiter limiter;

    RestClientShopbyClient(RestClient http, RedisTokenBucketRateLimiter limiter) {
        this.http = http;
        this.limiter = limiter;
    }

    @Override
    public OrderSearchPage searchOrdersByStatusChange(LocalDateTime start, LocalDateTime end, int pageNumber, int pageSize) {
        JsonNode body = call(() -> http.get()
                .uri(u -> u.path("/orders")
                        .queryParam("startYmdt", start.format(OrderStatusChangeJson.YMDT))
                        .queryParam("endYmdt", end.format(OrderStatusChangeJson.YMDT))
                        .queryParam("searchDateType", "STATUS_CHANGE")
                        .queryParam("pageNumber", pageNumber)
                        .queryParam("pageSize", pageSize)
                        .build())
                .header("Version", "1.1")
                .retrieve().toEntity(JsonNode.class));
        List<OrderStatusChange> options = new ArrayList<>();
        int orderCount = 0;
        for (JsonNode order : body.path("contents")) {
            orderCount++;
            String orderNo = order.path("orderNo").asText(null);
            for (JsonNode group : order.path("deliveryGroups")) {
                Long deliveryNo = group.hasNonNull("deliveryNo") ? group.get("deliveryNo").asLong() : null;
                String company = group.hasNonNull("deliveryCompanyType") ? group.get("deliveryCompanyType").asText() : null;
                String invoice = group.hasNonNull("invoiceNo") ? group.get("invoiceNo").asText() : null;
                for (JsonNode product : group.path("orderProducts")) {
                    String productName = product.path("productName").asText(null);
                    for (JsonNode option : product.path("orderProductOptions")) {
                        options.add(OrderStatusChangeJson.fromNode(option, orderNo, deliveryNo, productName, company, invoice));
                    }
                }
            }
        }
        return new OrderSearchPage(body.path("totalCount").asInt(), orderCount, options);
    }

    @Override
    public FailedWebhookPage searchFailedWebhooks(LocalDateTime start, LocalDateTime end, int page, int pageSize) {
        JsonNode body = call(() -> http.get()
                .uri(u -> u.path("/webhooks/failed")
                        .queryParam("startDateTime", start.format(OrderStatusChangeJson.YMDT))
                        .queryParam("endDateTime", end.format(OrderStatusChangeJson.YMDT))
                        .queryParam("page", page)
                        .queryParam("pageSize", pageSize)
                        .build())
                .header("Version", "1.0")
                .retrieve().toEntity(JsonNode.class));
        List<FailedWebhook> contents = new ArrayList<>();
        for (JsonNode n : body.path("contents")) {
            contents.add(new FailedWebhook(n.path("eventType").asText(null),
                    OrderStatusChangeJson.time(n, "exceptionDateTime"),
                    n.hasNonNull("data") ? n.get("data").asText() : null));
        }
        return new FailedWebhookPage(body.path("totalCount").asInt(), contents);
    }

    @Override
    public void prepareProduct(List<Long> orderOptionNos) {
        call(() -> http.put().uri("/orders/prepare-product").header("Version", "1.0")
                .contentType(MediaType.APPLICATION_JSON).body(orderOptionNos)
                .retrieve().toBodilessEntity());
    }

    @Override
    public void holdDelivery(List<Long> orderOptionNos, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderOptionNos", orderOptionNos);
        body.put("holdDeliveryReason", reason);
        call(() -> http.put().uri("/orders/hold-delivery").header("Version", "1.0")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toBodilessEntity());
    }

    @Override
    public InvoiceChangeResult changeStatusByShippingNo(String orderStatusType, List<InvoiceChange> changes) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (InvoiceChange c : changes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("shippingNo", c.shippingNo());
            m.put("deliveryCompanyType", c.deliveryCompanyType());
            m.put("invoiceNo", c.invoiceNo());
            list.add(m);
        }
        Map<String, Object> body = Map.of("orderStatusType", orderStatusType, "changeStatusList", list);
        JsonNode res = call(() -> http.put().uri("/orders/change-status/by-shipping-no").header("Version", "1.0")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toEntity(JsonNode.class));
        List<InvoiceFailure> failures = new ArrayList<>();
        for (JsonNode f : res.path("failures")) {
            JsonNode o = f.path("originalRequest");
            failures.add(new InvoiceFailure(
                    new InvoiceChange(o.path("shippingNo").asLong(), o.path("deliveryCompanyType").asText(null),
                            o.path("invoiceNo").asText(null)),
                    f.path("errorCode").asText(null), f.path("errorMessage").asText(null)));
        }
        return new InvoiceChangeResult(res.path("totalCount").asInt(), res.path("successCount").asInt(),
                res.path("failCount").asInt(), failures);
    }

    @Override
    public void approveClaim(long claimNo) {
        call(() -> http.put().uri("/claims/{claimNo}/approve", claimNo).header("Version", "1.0")
                .retrieve().toBodilessEntity());
    }

    @Override
    public void alreadyDelivery(long claimNo, String deliveryCompanyType, String invoiceNo, LocalDate releaseYmd) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deliveryCompanyType", deliveryCompanyType);
        body.put("invoiceNo", invoiceNo);
        body.put("releaseYmd", releaseYmd.toString());
        call(() -> http.put().uri("/claims/{claimNo}/already-delivery", claimNo).header("Version", "1.0")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toBodilessEntity());
    }

    /** 리미터 → 호출 → EXHAUSTED/429 처리 → 2xx가 아니면 ApiException */
    private <T> T call(Supplier<ResponseEntity<T>> request) {
        limiter.acquire();
        try {
            ResponseEntity<T> res = request.get();
            checkExhausted(res.getHeaders());
            return res.getBody();
        } catch (RestClientResponseException e) {
            HttpHeaders headers = e.getResponseHeaders();
            if (headers != null) {
                checkExhausted(headers);
            }
            HttpStatusCode status = e.getStatusCode();
            if (status.value() == 429) {
                limiter.exhausted();
                throw new RateLimitedException("샵바이 429");
            }
            throw new ApiException(status.value(), e.getResponseBodyAsString());
        }
    }

    private void checkExhausted(HttpHeaders headers) {
        if (EXHAUSTED.equalsIgnoreCase(headers.getFirst(RATE_LIMIT_HEADER))) {
            limiter.exhausted();
        }
    }
}
