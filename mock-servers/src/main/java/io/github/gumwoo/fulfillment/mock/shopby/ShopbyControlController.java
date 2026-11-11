package io.github.gumwoo.fulfillment.mock.shopby;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 테스트·측정용 제어 API: 주문 만들기, 상태 바꾸기(웹훅 발송), 장애 주입, 기록 조회. 샵바이 스펙과 무관하다. */
@RestController
@RequestMapping("/_mock/shopby")
public class ShopbyControlController {

    private final ShopbyMockState state;
    private final WebhookSender webhooks;
    private final ShopbyFaults faults;
    private final ShopbyCallRecorder calls;

    public ShopbyControlController(ShopbyMockState state, WebhookSender webhooks, ShopbyFaults faults,
                                   ShopbyCallRecorder calls) {
        this.state = state;
        this.webhooks = webhooks;
        this.faults = faults;
        this.calls = calls;
    }

    /**
     * 주문 생성. body: {orderNo, deliveryNo, orderStatusType(기본 PAY_DONE), sendWebhook(기본 true),
     * options:[{orderProductOptionNo, productName, optionName, optionValue, orderCnt}]}
     */
    @PostMapping("/orders")
    public ResponseEntity<Void> createOrder(@RequestBody JsonNode body) {
        LocalDateTime now = state.now();
        String status = body.path("orderStatusType").asText("PAY_DONE");
        List<ObjectNode> elements = new ArrayList<>();
        for (JsonNode n : body.path("options")) {
            MockOption o = new MockOption();
            o.orderProductOptionNo = n.path("orderProductOptionNo").asLong();
            o.orderNo = body.path("orderNo").asText();
            o.deliveryNo = n.has("deliveryNo") ? n.path("deliveryNo").asLong() : body.path("deliveryNo").asLong();
            o.productName = n.path("productName").asText("상품");
            o.optionName = n.path("optionName").asText(null);
            o.optionValue = n.path("optionValue").asText(null);
            o.orderCnt = n.path("orderCnt").asInt(1);
            o.orderYmdt = now;
            o.changeStatus(status, now);
            state.put(o);
            elements.add(state.optionJson(o, true));
        }
        if (body.path("sendWebhook").asBoolean(true)) {
            webhooks.send(elements, now);
        }
        return ResponseEntity.noContent().build();
    }

    /** 상태 변경. body: {orderStatusType, sendWebhook(기본 true)} */
    @PostMapping("/options/{no}/status")
    public ResponseEntity<Void> changeStatus(@PathVariable long no, @RequestBody JsonNode body) {
        MockOption o = state.find(no).orElseThrow();
        LocalDateTime now = state.now();
        synchronized (state) {
            o.changeStatus(body.path("orderStatusType").asText(), now);
        }
        if (body.path("sendWebhook").asBoolean(true)) {
            webhooks.send(List.of(state.optionJson(o, true)), now);
        }
        return ResponseEntity.noContent().build();
    }

    /** 클레임 변경(취소 신청 등). body: {claimStatusType, claimNo, sendWebhook(기본 true)} */
    @PostMapping("/options/{no}/claim")
    public ResponseEntity<Void> changeClaim(@PathVariable long no, @RequestBody JsonNode body) {
        MockOption o = state.find(no).orElseThrow();
        LocalDateTime now = state.now();
        synchronized (state) {
            o.changeClaim(body.path("claimStatusType").asText(null),
                    body.hasNonNull("claimNo") ? body.get("claimNo").asLong() : null, now);
        }
        if (body.path("sendWebhook").asBoolean(true)) {
            webhooks.send(List.of(state.optionJson(o, true)), now);
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/options/{no}")
    public ResponseEntity<JsonNode> option(@PathVariable long no) {
        return state.find(no).<ResponseEntity<JsonNode>>map(o -> ResponseEntity.ok(state.optionJson(o, false)))
                .orElse(ResponseEntity.notFound().build());
    }

    /** 대사·측정용: 모든 옵션의 현재 상태 */
    @GetMapping("/options")
    public List<JsonNode> options() {
        return state.all().stream().<JsonNode>map(o -> state.optionJson(o, false)).toList();
    }

    @PostMapping("/webhook-config")
    public ResponseEntity<Void> webhookConfig(@RequestBody WebhookSender.Config config) {
        webhooks.configure(config);
        return ResponseEntity.noContent().build();
    }

    /** 실패 내역 직접 추가. body: {eventType, exceptionDateTime, data} */
    @PostMapping("/failed-webhooks")
    public ResponseEntity<Void> addFailed(@RequestBody JsonNode body) {
        webhooks.addFailed(new WebhookSender.FailedRecord(body.path("eventType").asText("CHANGE_ORDER_STATUS"),
                ShopbyApiController.parse(body.path("exceptionDateTime").asText()), body.path("data").asText(""), "mock"));
        return ResponseEntity.noContent().build();
    }

    /** 송장 일괄 반영에서 한 번만 실패시킬 배송번호. body: [shippingNo, ...] */
    @PostMapping("/faults/invoice-fail-once")
    public ResponseEntity<Void> failInvoiceOnce(@RequestBody List<Long> shippingNos) {
        faults.failInvoiceOnce(new HashSet<>(shippingNos));
        return ResponseEntity.noContent().build();
    }

    /** 호출 제한 장애 주입. body: {next429, nextExhausted, limitPerSecond} */
    @PostMapping("/faults/rate-limit")
    public ResponseEntity<Void> rateLimitFaults(@RequestBody JsonNode body) {
        calls.inject(body.path("next429").asInt(0), body.path("nextExhausted").asInt(0),
                body.path("limitPerSecond").asInt(0));
        return ResponseEntity.noContent().build();
    }

    /** 배송보류 요청에 이 옵션이 들어 있으면 한 번만 실패. body: [optionNo, ...] */
    @PostMapping("/faults/hold-fail-once")
    public ResponseEntity<Void> failHoldOnce(@RequestBody List<Long> optionNos) {
        faults.failHoldOnce(new HashSet<>(optionNos));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("callsByPath", calls.callsByPath());
        m.put("maxCallsInAnySecond", calls.maxCallsInAnySecond());
        m.put("rejected429", calls.rejected429());
        m.put("exhaustedSent", calls.exhaustedSent());
        m.put("maxCallsInAnySlidingSecond", calls.maxCallsInAnySlidingSecond());
        m.put("webhooksSent", webhooks.sentCount());
        m.put("webhooksDropped", webhooks.droppedCount());
        m.put("invoiceRequests", faults.invoiceRequests());
        m.put("holdCalls", faults.holdCalls());
        m.put("claimCalls", faults.claimCalls());
        Set<Long> pending = faults.pendingInvoiceFailures();
        m.put("pendingInvoiceFailures", pending);
        return m;
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        state.reset();
        webhooks.reset();
        faults.reset();
        calls.reset();
        return ResponseEntity.noContent().build();
    }
}
