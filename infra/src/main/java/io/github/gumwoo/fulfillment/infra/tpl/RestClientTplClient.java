package io.github.gumwoo.fulfillment.infra.tpl;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.gumwoo.fulfillment.core.port.TplClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** 3PL mock 어댑터. 요청·응답 형태는 이 프로젝트의 mock 정의다 (TplClient 주석 참고). */
public class RestClientTplClient implements TplClient {

    private final RestClient http;

    public RestClientTplClient(RestClient http) {
        this.http = http;
    }

    /** 연결 거부·타임아웃(ResourceAccessException)도 5xx와 같은 일시 장애로 본다 (ADR-0008) */
    private static <T> T guard(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (org.springframework.web.client.ResourceAccessException e) {
            throw new TplException("3PL 연결 실패: " + e.getMessage());
        }
    }

    @Override
    public Registration register(OrderRequest r) {
        return guard(() -> doRegister(r));
    }

    private Registration doRegister(OrderRequest r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderProductOptionNo", r.orderProductOptionNo());
        body.put("orderNo", r.orderNo());
        body.put("deliveryNo", r.deliveryNo());
        body.put("productName", r.productName());
        body.put("optionName", r.optionName());
        body.put("optionValue", r.optionValue());
        body.put("quantity", r.quantity());
        try {
            JsonNode res = http.post().uri("/tpl/orders").contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(JsonNode.class);
            return new Registration(true, Status.valueOf(res.path("status").asText()), null, null);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 422) {
                JsonNode err = e.getResponseBodyAs(JsonNode.class);
                return new Registration(false, null, err == null ? "REJECTED" : err.path("code").asText("REJECTED"),
                        err == null ? null : err.path("message").asText(null));
            }
            throw new TplException("3PL 등록 실패 " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString());
        }
    }

    @Override
    public Optional<TplOrder> find(long optionNo) {
        return guard(() -> doFind(optionNo));
    }

    private Optional<TplOrder> doFind(long optionNo) {
        try {
            JsonNode res = http.get().uri("/tpl/orders/{no}", optionNo).retrieve().body(JsonNode.class);
            return Optional.of(toOrder(res));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw new TplException("3PL 조회 실패 " + e.getStatusCode().value());
        }
    }

    @Override
    public CancelResult cancel(long optionNo) {
        return guard(() -> doCancel(optionNo));
    }

    private CancelResult doCancel(long optionNo) {
        try {
            JsonNode res = http.post().uri("/tpl/orders/{no}/cancel", optionNo).retrieve().body(JsonNode.class);
            return new CancelResult(true, res.path("fee").asInt(0), Status.CANCELED);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 409) {
                JsonNode err = e.getResponseBodyAs(JsonNode.class);
                return new CancelResult(false, 0, err == null ? null : Status.valueOf(err.path("status").asText()));
            }
            throw new TplException("3PL 취소 실패 " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString());
        }
    }

    @Override
    public EventPage events(long afterSeq, int limit) {
        return guard(() -> doEvents(afterSeq, limit));
    }

    private EventPage doEvents(long afterSeq, int limit) {
        JsonNode res = http.get().uri(u -> u.path("/tpl/events").queryParam("afterSeq", afterSeq)
                .queryParam("limit", limit).build()).retrieve().body(JsonNode.class);
        List<Event> events = new ArrayList<>();
        for (JsonNode e : res.path("events")) {
            events.add(new Event(e.path("seq").asLong(), e.path("type").asText(), e.path("orderProductOptionNo").asLong(),
                    e.hasNonNull("status") ? Status.valueOf(e.get("status").asText()) : null,
                    e.hasNonNull("invoiceNo") ? e.get("invoiceNo").asText() : null,
                    e.hasNonNull("deliveryCompanyType") ? e.get("deliveryCompanyType").asText() : null));
        }
        return new EventPage(events, res.path("lastSeq").asLong(afterSeq));
    }

    private static TplOrder toOrder(JsonNode n) {
        return new TplOrder(n.path("orderProductOptionNo").asLong(), Status.valueOf(n.path("status").asText()),
                n.hasNonNull("invoiceNo") ? n.get("invoiceNo").asText() : null,
                n.hasNonNull("deliveryCompanyType") ? n.get("deliveryCompanyType").asText() : null);
    }
}
