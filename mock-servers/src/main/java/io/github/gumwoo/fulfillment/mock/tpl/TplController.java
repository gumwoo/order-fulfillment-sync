package io.github.gumwoo.fulfillment.mock.tpl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 3PL mock API (/tpl/**)와 제어 API (/_mock/tpl/**). 형태는 이 프로젝트가 정한 것이다 (TplMockState 주석). */
@RestController
public class TplController {

    private final TplMockState state;
    private final ObjectMapper mapper;

    public TplController(TplMockState state, ObjectMapper mapper) {
        this.state = state;
        this.mapper = mapper;
    }

    @PostMapping("/tpl/orders")
    public ResponseEntity<JsonNode> register(@RequestBody JsonNode body) {
        TplMockState.Order req = new TplMockState.Order();
        req.orderProductOptionNo = body.path("orderProductOptionNo").asLong();
        req.orderNo = body.path("orderNo").asText(null);
        req.deliveryNo = body.hasNonNull("deliveryNo") ? body.get("deliveryNo").asLong() : null;
        req.productName = body.path("productName").asText(null);
        req.optionName = body.path("optionName").asText(null);
        req.optionValue = body.path("optionValue").asText(null);
        req.quantity = body.path("quantity").asInt(1);
        TplMockState.Order o = state.register(req);
        if (o == null) {
            ObjectNode err = mapper.createObjectNode();
            err.put("code", "OPTION_NOT_MAPPED");
            err.put("message", "옵션명 매핑 없음: " + TplMockState.mappingKey(req.optionName, req.optionValue));
            return ResponseEntity.status(422).body(err);
        }
        return ResponseEntity.ok(json(o));
    }

    @GetMapping("/tpl/orders/{no}")
    public ResponseEntity<JsonNode> find(@PathVariable long no) {
        TplMockState.Order o = state.find(no);
        return o == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(json(o));
    }

    @PostMapping("/tpl/orders/{no}/cancel")
    public ResponseEntity<JsonNode> cancel(@PathVariable long no) {
        TplMockState.Order before = state.find(no);
        if (before == null) {
            return ResponseEntity.notFound().build();
        }
        TplMockState.Order o = state.cancel(no);
        if (o == null) {
            ObjectNode err = mapper.createObjectNode();
            err.put("code", "NOT_CANCELABLE");
            err.put("status", before.status);
            return ResponseEntity.status(409).body(err);
        }
        ObjectNode res = json(o);
        res.put("fee", o.cancelFee);
        return ResponseEntity.ok(res);
    }

    @GetMapping("/tpl/events")
    public JsonNode events(@RequestParam(defaultValue = "0") long afterSeq, @RequestParam(defaultValue = "100") int limit) {
        List<TplMockState.Event> events = state.eventsAfter(afterSeq, limit);
        ObjectNode root = mapper.createObjectNode();
        ArrayNode arr = root.putArray("events");
        events.forEach(e -> arr.add(mapper.valueToTree(e)));
        root.put("lastSeq", events.isEmpty() ? afterSeq : events.get(events.size() - 1).seq());
        return root;
    }

    // ---- 제어 API ----

    /** body: {enforce, allowed:["옵션명|옵션값", ...]} */
    @PostMapping("/_mock/tpl/mapping")
    public ResponseEntity<Void> mapping(@RequestBody JsonNode body) {
        List<String> allowed = new ArrayList<>();
        body.path("allowed").forEach(n -> allowed.add(n.asText()));
        state.configureMapping(body.path("enforce").asBoolean(false), allowed);
        return ResponseEntity.noContent().build();
    }

    /** body: {key: "옵션명|옵션값"} — 매핑표에 추가 (CS가 3PL 쪽 매핑을 고친 상황) */
    @PostMapping("/_mock/tpl/mapping/allow")
    public ResponseEntity<Void> allow(@RequestBody JsonNode body) {
        state.allowMapping(body.path("key").asText());
        return ResponseEntity.noContent().build();
    }

    /** body: {status} */
    @PostMapping("/_mock/tpl/orders/{no}/advance")
    public ResponseEntity<Void> advance(@PathVariable long no, @RequestBody JsonNode body) {
        state.advance(no, body.path("status").asText());
        return ResponseEntity.noContent().build();
    }

    /** body: {invoiceNo, deliveryCompanyType} */
    @PostMapping("/_mock/tpl/orders/{no}/invoice")
    public ResponseEntity<Void> invoice(@PathVariable long no, @RequestBody JsonNode body) {
        state.issueInvoice(no, body.path("invoiceNo").asText(), body.path("deliveryCompanyType").asText("CJ"));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/_mock/tpl/stats")
    public Map<String, Object> stats() {
        return state.stats();
    }

    @PostMapping("/_mock/tpl/reset")
    public ResponseEntity<Void> reset() {
        state.reset();
        return ResponseEntity.noContent().build();
    }

    private ObjectNode json(TplMockState.Order o) {
        ObjectNode n = mapper.createObjectNode();
        n.put("orderProductOptionNo", o.orderProductOptionNo);
        n.put("orderNo", o.orderNo);
        if (o.deliveryNo != null) {
            n.put("deliveryNo", o.deliveryNo);
        }
        n.put("optionName", o.optionName);
        n.put("optionValue", o.optionValue);
        n.put("status", o.status);
        n.put("invoiceNo", o.invoiceNo);
        n.put("deliveryCompanyType", o.deliveryCompanyType);
        return n;
    }
}
