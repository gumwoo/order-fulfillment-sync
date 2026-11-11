package io.github.gumwoo.fulfillment.infra.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.gumwoo.fulfillment.core.order.OrderStatus;
import io.github.gumwoo.fulfillment.core.order.OrderStatusChange;
import io.github.gumwoo.fulfillment.core.order.OrderStatusChange.StageTimes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 샵바이 CHANGE_ORDER_STATUS 원소(웹훅)와 주문조회 주문옵션의 JSON ↔ {@link OrderStatusChange}.
 * 필드 이름은 external-facts.md에 확인한 것만 쓴다. 큐 메시지 본문도 이 형태(원소 JSON)다.
 */
public class OrderStatusChangeJson {

    public static final DateTimeFormatter YMDT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper objectMapper;

    public OrderStatusChangeJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public OrderStatusChange parse(String json) {
        try {
            return fromNode(objectMapper.readTree(json));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("주문상태변경 JSON 파싱 실패", e);
        }
    }

    /** 원소(또는 주문옵션) 노드. orderNo·deliveryNo·productName은 상위 노드에서 채울 수 있게 비어 있어도 된다. */
    public static OrderStatusChange fromNode(JsonNode n) {
        return fromNode(n, null, null, null, null, null);
    }

    public static OrderStatusChange fromNode(JsonNode n, String orderNo, Long deliveryNo, String productName,
                                             String deliveryCompanyType, String invoiceNo) {
        return new OrderStatusChange(
                n.path("orderProductOptionNo").asLong(),
                orElse(text(n, "orderNo"), orderNo),
                n.hasNonNull("deliveryNo") ? Long.valueOf(n.get("deliveryNo").asLong()) : deliveryNo,
                OrderStatus.valueOf(n.path("orderStatusType").asText()),
                text(n, "claimStatusType"),
                n.hasNonNull("claimNo") ? Long.valueOf(n.get("claimNo").asLong()) : null,
                orElse(text(n, "productName"), productName),
                text(n, "optionName"),
                text(n, "optionValue"),
                n.path("orderCnt").asInt(0),
                orElse(text(n, "deliveryCompanyType"), deliveryCompanyType),
                orElse(text(n, "invoiceNo"), invoiceNo),
                new StageTimes(
                        time(n, "orderYmdt"), time(n, "payYmdt"), time(n, "orderAcceptYmdt"),
                        time(n, "releaseReadyYmdt"), time(n, "releaseYmdt"), time(n, "deliveryCompleteYmdt"),
                        time(n, "buyConfirmYmdt")));
    }

    /** 재조회 결과를 큐에 넣을 때 웹훅 원소와 같은 형태로 만든다 */
    public String toElementJson(OrderStatusChange c) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("eventType", "CHANGE_ORDER_STATUS");
        n.put("orderProductOptionNo", c.orderProductOptionNo());
        n.put("orderNo", c.orderNo());
        if (c.deliveryNo() != null) {
            n.put("deliveryNo", c.deliveryNo());
        }
        n.put("orderStatusType", c.orderStatus().name());
        n.put("claimStatusType", c.claimStatusType());
        if (c.claimNo() != null) {
            n.put("claimNo", c.claimNo());
        } else {
            n.putNull("claimNo");
        }
        n.put("productName", c.productName());
        n.put("optionName", c.optionName());
        n.put("optionValue", c.optionValue());
        n.put("orderCnt", c.orderCnt());
        n.put("deliveryCompanyType", c.deliveryCompanyType());
        n.put("invoiceNo", c.invoiceNo());
        StageTimes t = c.stageTimes();
        putTime(n, "orderYmdt", t.orderYmdt());
        putTime(n, "payYmdt", t.payYmdt());
        putTime(n, "orderAcceptYmdt", t.orderAcceptYmdt());
        putTime(n, "releaseReadyYmdt", t.releaseReadyYmdt());
        putTime(n, "releaseYmdt", t.releaseYmdt());
        putTime(n, "deliveryCompleteYmdt", t.deliveryCompleteYmdt());
        putTime(n, "buyConfirmYmdt", t.buyConfirmYmdt());
        return n.toString();
    }

    private static void putTime(ObjectNode n, String field, LocalDateTime v) {
        if (v == null) {
            n.putNull(field);
        } else {
            n.put(field, v.format(YMDT));
        }
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asText() : null;
    }

    private static String orElse(String v, String fallback) {
        return v != null ? v : fallback;
    }

    public static LocalDateTime time(JsonNode n, String field) {
        String v = text(n, field);
        return v == null || v.isBlank() ? null : LocalDateTime.parse(v, YMDT);
    }
}
