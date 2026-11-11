package io.github.gumwoo.fulfillment.infra.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.gumwoo.fulfillment.core.port.TplClient.Event;
import io.github.gumwoo.fulfillment.core.port.TplClient.Status;

/** TPL_EVENT 큐 메시지 본문 ↔ {@link Event} */
public class TplEventJson {

    private final ObjectMapper mapper;

    public TplEventJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Event e) {
        ObjectNode n = mapper.createObjectNode();
        n.put("seq", e.seq());
        n.put("type", e.type());
        n.put("orderProductOptionNo", e.orderProductOptionNo());
        n.put("status", e.status() == null ? null : e.status().name());
        n.put("invoiceNo", e.invoiceNo());
        n.put("deliveryCompanyType", e.deliveryCompanyType());
        return n.toString();
    }

    public Event read(String json) {
        try {
            JsonNode n = mapper.readTree(json);
            return new Event(n.path("seq").asLong(), n.path("type").asText(), n.path("orderProductOptionNo").asLong(),
                    n.hasNonNull("status") ? Status.valueOf(n.get("status").asText()) : null,
                    n.hasNonNull("invoiceNo") ? n.get("invoiceNo").asText() : null,
                    n.hasNonNull("deliveryCompanyType") ? n.get("deliveryCompanyType").asText() : null);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("TPL_EVENT 본문 파싱 실패", e);
        }
    }
}
