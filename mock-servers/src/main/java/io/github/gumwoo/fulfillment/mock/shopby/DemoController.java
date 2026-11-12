package io.github.gumwoo.fulfillment.mock.shopby;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.gumwoo.fulfillment.mock.tpl.TplMockState;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 데모 대시보드용 일괄 조작 API (/_mock/demo/**). 샵바이·3PL 스펙과 무관한 시연 도구다.
 * 쇼핑몰에서 주문이 쏟아지고, 웹훅이 유실되고, 창고가 출고를 진행하고, 고객이 취소하는 상황을 버튼 하나로 만든다.
 */
@RestController
@RequestMapping("/_mock/demo")
public class DemoController {

    private final ShopbyMockState state;
    private final WebhookSender webhooks;
    private final ShopbyFaults faults;
    private final ShopbyCallRecorder calls;
    private final TplMockState tpl;
    private final String inboundWebhookUrl;
    private final AtomicLong nextOptionNo = new AtomicLong(1_000_001);
    private final AtomicLong nextClaimNo = new AtomicLong(500_001);
    private long seed = 1;

    public DemoController(ShopbyMockState state, WebhookSender webhooks, ShopbyFaults faults, ShopbyCallRecorder calls,
                          TplMockState tpl,
                          @Value("${app.demo.inbound-webhook-url:http://localhost:8081/webhooks/shopby}") String inboundWebhookUrl) {
        this.state = state;
        this.webhooks = webhooks;
        this.faults = faults;
        this.calls = calls;
        this.tpl = tpl;
        this.inboundWebhookUrl = inboundWebhookUrl;
    }

    /**
     * 주문 N건 생성(결제완료, 웹훅 발송). body: {count, dropRatio, recordDroppedAsFailed, duplicates, unmappedRatio, webhookUrl(선택)}
     * unmappedRatio만큼은 3PL 매핑표에 없는 옵션값("빨강")으로 만든다 (매핑 검사를 켠다).
     */
    @PostMapping("/orders")
    public Map<String, Object> createOrders(@RequestBody JsonNode body) {
        int count = Math.min(body.path("count").asInt(50), 1000);
        double unmappedRatio = body.path("unmappedRatio").asDouble(0);
        String url = body.hasNonNull("webhookUrl") ? body.get("webhookUrl").asText() : inboundWebhookUrl;
        webhooks.configure(new WebhookSender.Config(url, body.path("dropRatio").asDouble(0),
                body.path("recordDroppedAsFailed").asBoolean(true), Math.max(1, body.path("duplicates").asInt(1)), seed++));
        if (unmappedRatio > 0) {
            tpl.configureMapping(true, List.of("색상|검정"));
        }
        java.util.Random random = new java.util.Random(seed++);
        long first = nextOptionNo.get();
        int unmapped = 0;
        for (int i = 0; i < count; i++) {
            long no = nextOptionNo.getAndIncrement();
            boolean bad = random.nextDouble() < unmappedRatio;
            unmapped += bad ? 1 : 0;
            LocalDateTime now = state.now();
            MockOption o = new MockOption();
            o.orderProductOptionNo = no;
            o.orderNo = "DEMO" + no;
            o.deliveryNo = 9_000_000 + no;
            o.productName = "데모 티셔츠";
            o.optionName = "색상";
            o.optionValue = bad ? "빨강" : "검정";
            o.orderYmdt = now;
            o.changeStatus("PAY_DONE", now);
            state.put(o);
            webhooks.send(List.of(state.optionJson(o, true)), now);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("created", count);
        r.put("firstOptionNo", first);
        r.put("lastOptionNo", nextOptionNo.get() - 1);
        r.put("unmapped", unmapped);
        r.put("webhooksDropped", webhooks.droppedCount());
        return r;
    }

    /** body: {next429, nextExhausted} */
    @PostMapping("/faults")
    public ResponseEntity<Void> faults(@RequestBody JsonNode body) {
        calls.inject(body.path("next429").asInt(0), body.path("nextExhausted").asInt(0), 0);
        return ResponseEntity.noContent().build();
    }

    /** 3PL 출고 일괄 진행. body: {from, to, max}. to가 RELEASED면 송장도 발급한다 */
    @PostMapping("/tpl/advance")
    public Map<String, Object> advance(@RequestBody JsonNode body) {
        String from = body.path("from").asText("REQUESTED");
        String to = body.path("to").asText("WORKING");
        int max = body.path("max").asInt(1000);
        List<Long> targets = tpl.optionNosByStatus(from).stream().limit(max).toList();
        for (long no : targets) {
            tpl.advance(no, to);
            if ("RELEASED".equals(to)) {
                tpl.issueInvoice(no, "INV" + no, "CJ");
            }
        }
        return Map.of("advanced", targets.size(), "from", from, "to", to);
    }

    /** 고객 취소 신청. body: {count, tplStatus} — 3PL이 해당 상태인 주문 중 클레임 없는 것에 취소 신청 */
    @PostMapping("/cancel")
    public Map<String, Object> cancel(@RequestBody JsonNode body) {
        int count = body.path("count").asInt(5);
        String tplStatus = body.path("tplStatus").asText("REQUESTED");
        List<Long> requested = new ArrayList<>();
        for (long no : tpl.optionNosByStatus(tplStatus)) {
            if (requested.size() >= count) {
                break;
            }
            MockOption o = state.find(no).orElse(null);
            if (o == null || o.claimStatusType != null) {
                continue;
            }
            LocalDateTime now = state.now();
            synchronized (state) {
                o.changeClaim("CANCEL_REQUEST", nextClaimNo.getAndIncrement(), now);
            }
            webhooks.send(List.of(state.optionJson(o, true)), now);
            requested.add(no);
        }
        return Map.of("cancelRequested", requested.size(), "tplStatus", tplStatus);
    }

    /** 3PL 매핑 수정: CS가 3PL 쪽 옵션명 매핑을 고친 상황 */
    @PostMapping("/mapping/fix")
    public ResponseEntity<Void> fixMapping() {
        tpl.allowMapping("색상|빨강");
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        Map<String, Long> claims = new LinkedHashMap<>();
        long hold = 0;
        for (MockOption o : state.all()) {
            byStatus.merge(o.orderStatusType, 1L, Long::sum);
            if (o.claimStatusType != null) {
                claims.merge(o.claimStatusType, 1L, Long::sum);
            }
            hold += o.holdDelivery ? 1 : 0;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("shopby", Map.of("orderOptions", state.all().size(), "byStatus", byStatus, "claims", claims, "holdDelivery", hold));
        m.put("tpl", tpl.countByStatus());
        m.put("webhooks", Map.of("sent", webhooks.sentCount(), "dropped", webhooks.droppedCount()));
        m.put("shopbyApi", Map.of("maxCallsInAnySecond", calls.maxCallsInAnySecond(), "rejected429", calls.rejected429(),
                "callsByPath", calls.callsByPath()));
        return m;
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        state.reset();
        webhooks.reset();
        faults.reset();
        calls.reset();
        tpl.reset();
        return ResponseEntity.noContent().build();
    }
}
