package io.github.gumwoo.fulfillment.mock.shopby;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 샵바이 서버 API mock. 경로·파라미터·응답 필드와 제약(건수 한도, 전체 실패/부분 실패)은 external-facts.md를 따른다.
 * 오류 응답 본문 형식은 확인하지 못해 {code, message}로 임의로 둔다 (클라이언트는 상태 코드만 본다).
 */
@RestController
public class ShopbyApiController {

    private static final Set<String> BEFORE_SHIPMENT = Set.of("PAY_DONE", "PRODUCT_PREPARE", "DELIVERY_PREPARE");
    private static final Set<String> HOLDABLE = Set.of("PRODUCT_PREPARE", "DELIVERY_PREPARE");

    private final ShopbyMockState state;
    private final WebhookSender webhooks;
    private final ShopbyFaults faults;
    private final ObjectMapper mapper;

    public ShopbyApiController(ShopbyMockState state, WebhookSender webhooks, ShopbyFaults faults, ObjectMapper mapper) {
        this.state = state;
        this.webhooks = webhooks;
        this.faults = faults;
        this.mapper = mapper;
    }

    @GetMapping("/orders")
    public ResponseEntity<JsonNode> orders(@RequestParam String startYmdt, @RequestParam String endYmdt,
                                           @RequestParam(defaultValue = "ORDER_START") String searchDateType,
                                           @RequestParam(defaultValue = "1") int pageNumber,
                                           @RequestParam(defaultValue = "20") int pageSize) {
        if (!"STATUS_CHANGE".equals(searchDateType)) {
            return error(400, "MOCK_UNSUPPORTED", "mock은 searchDateType=STATUS_CHANGE만 지원한다");
        }
        if (pageSize > 200 || pageSize < 1 || pageNumber < 1) {
            return error(400, "INVALID_PAGE", "pageSize는 최대 200");
        }
        return ResponseEntity.ok(state.searchByStatusChange(parse(startYmdt), parse(endYmdt), pageNumber, pageSize));
    }

    @GetMapping("/webhooks/failed")
    public ResponseEntity<JsonNode> failedWebhooks(@RequestParam String startDateTime, @RequestParam String endDateTime,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "10") int pageSize) {
        LocalDateTime start = parse(startDateTime);
        LocalDateTime end = parse(endDateTime);
        if (Duration.between(start, end).compareTo(Duration.ofDays(7)) > 0) {
            return error(400, "INVALID_PERIOD", "검색 기간은 최대 7일");
        }
        List<WebhookSender.FailedRecord> all = webhooks.failedBetween(start, end);
        int from = Math.max(0, (page - 1) * pageSize);
        int to = Math.min(all.size(), from + pageSize);
        ObjectNode root = mapper.createObjectNode();
        root.put("totalCount", all.size());
        ArrayNode contents = root.putArray("contents");
        for (WebhookSender.FailedRecord r : from < to ? all.subList(from, to) : List.<WebhookSender.FailedRecord>of()) {
            ObjectNode n = contents.addObject();
            n.put("httpMethod", "POST");
            n.put("webhookUrl", r.webhookUrl());
            n.put("solutionType", "SHOPBY");
            n.put("eventType", r.eventType());
            n.put("data", r.data());
            n.put("exceptionDateTime", r.exceptionDateTime().format(ShopbyMockState.YMDT));
            n.put("exceptionType", "class java.lang.Exception");
            n.put("exceptionMessage", "mock: 수신 실패");
        }
        return ResponseEntity.ok(root);
    }

    /** 결제완료 → 상품준비중. 하나라도 실패하면 전부 미반영 */
    @PutMapping("/orders/prepare-product")
    public ResponseEntity<JsonNode> prepareProduct(@RequestBody List<Long> orderOptionNos) {
        List<ObjectNode> changed;
        LocalDateTime now = state.now();
        synchronized (state) {
            List<MockOption> targets = new ArrayList<>();
            for (Long no : orderOptionNos) {
                MockOption o = state.find(no).orElse(null);
                if (o == null || !"PAY_DONE".equals(o.orderStatusType) || o.claimStatusType != null) {
                    return error(400, "OD_INVALID_STATUS", "결제완료 상태가 아닌 옵션: " + no);
                }
                targets.add(o);
            }
            targets.forEach(o -> o.changeStatus("PRODUCT_PREPARE", now));
            changed = targets.stream().map(o -> state.optionJson(o, true)).toList();
        }
        // 웹훅 발송은 잠금 밖에서 (동시 호출이 발송 때문에 줄 서지 않게)
        webhooks.send(changed, now);
        return ResponseEntity.noContent().build();
    }

    /** 배송보류. PRODUCT_PREPARE, DELIVERY_PREPARE만, 최대 1,000건, 하나라도 실패하면 전부 미반영 */
    @PutMapping("/orders/hold-delivery")
    public ResponseEntity<JsonNode> holdDelivery(@RequestBody JsonNode body) {
        synchronized (state) {
            return holdDeliveryLocked(body);
        }
    }

    private ResponseEntity<JsonNode> holdDeliveryLocked(JsonNode body) {
        JsonNode nos = body.path("orderOptionNos");
        if (nos.size() > 1000) {
            return error(400, "OD_TOO_MANY", "최대 1,000건");
        }
        List<MockOption> targets = new ArrayList<>();
        for (JsonNode n : nos) {
            MockOption o = state.find(n.asLong()).orElse(null);
            if (o == null || !HOLDABLE.contains(o.orderStatusType)) {
                return error(400, "OD_HOLD_NOT_ALLOWED", "배송보류할 수 없는 옵션: " + n.asLong());
            }
            targets.add(o);
        }
        faults.recordHoldCall(targets.size());
        if (faults.consumeHoldFailure(targets.stream().map(o -> o.orderProductOptionNo).toList())) {
            return error(400, "MOCK_INJECTED_FAILURE", "배송보류 실패(주입): 하나라도 실패하면 전부 미반영");
        }
        targets.forEach(o -> o.holdDelivery = true);
        return ResponseEntity.noContent().build();
    }

    /** 송장 일괄 반영 (배송번호 기준). 최대 100건, 건별 부분 실패 */
    @PutMapping("/orders/change-status/by-shipping-no")
    public ResponseEntity<JsonNode> changeStatusByShippingNo(@RequestBody JsonNode body) {
        List<ObjectNode> changed = new ArrayList<>();
        LocalDateTime now = state.now();
        ResponseEntity<JsonNode> res;
        synchronized (state) {
            res = changeStatusByShippingNoLocked(body, changed, now);
        }
        webhooks.send(changed, now);
        return res;
    }

    private ResponseEntity<JsonNode> changeStatusByShippingNoLocked(JsonNode body, List<ObjectNode> changed,
                                                                    LocalDateTime now) {
        String target = body.path("orderStatusType").asText();
        JsonNode list = body.path("changeStatusList");
        if (list.size() > 100) {
            return error(400, "OD_TOO_MANY", "최대 100건");
        }
        faults.recordInvoiceRequest(list);
        int success = 0;
        ArrayNode failures = mapper.createArrayNode();
        for (JsonNode item : list) {
            long shippingNo = item.path("shippingNo").asLong();
            List<MockOption> opts = state.all().stream().filter(o -> o.deliveryNo == shippingNo).toList();
            String errorCode = null;
            if (faults.consumeInvoiceFailure(shippingNo)) {
                errorCode = "MOCK_INJECTED_FAILURE";
            } else if (opts.isEmpty()) {
                errorCode = "OD_SHIPPING_NOT_FOUND";
            } else if (opts.stream().anyMatch(o -> !BEFORE_SHIPMENT.contains(o.orderStatusType) || o.holdDelivery
                    || o.claimStatusType != null)) {
                errorCode = "OD_INVALID_STATUS";
            }
            if (errorCode != null) {
                ObjectNode f = failures.addObject();
                f.set("originalRequest", item);
                f.put("errorCode", errorCode);
                f.put("errorMessage", "mock 실패: " + errorCode);
                continue;
            }
            for (MockOption o : opts) {
                o.invoiceNo = item.path("invoiceNo").asText(null);
                o.deliveryCompanyType = item.path("deliveryCompanyType").asText(null);
                o.changeStatus(target, now);
                changed.add(state.optionJson(o, true));
            }
            success++;
        }
        ObjectNode res = mapper.createObjectNode();
        res.put("totalCount", list.size());
        res.put("successCount", success);
        res.put("failCount", failures.size());
        res.set("failures", failures);
        return ResponseEntity.ok(res);
    }

    /** 클레임 승인. 주문 취소는 즉시 완료 (CANCEL_REQUEST → CANCEL_DONE) */
    @PutMapping("/claims/{claimNo}/approve")
    public ResponseEntity<JsonNode> approve(@PathVariable long claimNo) {
        LocalDateTime now = state.now();
        List<ObjectNode> changed;
        synchronized (state) {
            List<MockOption> opts = state.all().stream()
                    .filter(o -> o.claimNo != null && o.claimNo == claimNo && "CANCEL_REQUEST".equals(o.claimStatusType))
                    .toList();
            if (opts.isEmpty()) {
                return error(400, "CL_INVALID_CLAIM", "승인할 수 없는 클레임: " + claimNo);
            }
            opts.forEach(o -> {
                o.changeClaim("CANCEL_DONE", claimNo, now);
                o.holdDelivery = false;
            });
            faults.recordClaimCall("approve", claimNo);
            changed = opts.stream().map(o -> state.optionJson(o, true)).toList();
        }
        webhooks.send(changed, now);
        return ResponseEntity.noContent().build();
    }

    /** 이미출고: 클레임 철회 후 배송정보를 저장하고 배송중으로 변경 */
    @PutMapping("/claims/{claimNo}/already-delivery")
    public ResponseEntity<JsonNode> alreadyDelivery(@PathVariable long claimNo, @RequestBody JsonNode body) {
        LocalDateTime now = state.now();
        List<ObjectNode> changed;
        synchronized (state) {
            List<MockOption> opts = state.all().stream().filter(o -> o.claimNo != null && o.claimNo == claimNo).toList();
            if (opts.isEmpty() || body.path("releaseYmd").isMissingNode()) {
                return error(400, "CL_INVALID_CLAIM", "이미출고 처리할 수 없는 클레임: " + claimNo);
            }
            LocalDate.parse(body.path("releaseYmd").asText());
            opts.forEach(o -> {
                o.changeClaim(null, null, now);
                o.invoiceNo = body.path("invoiceNo").asText(null);
                o.deliveryCompanyType = body.path("deliveryCompanyType").asText(null);
                o.holdDelivery = false;
                o.changeStatus("DELIVERY_ING", now);
            });
            faults.recordClaimCall("already-delivery", claimNo);
            changed = opts.stream().map(o -> state.optionJson(o, true)).toList();
        }
        webhooks.send(changed, now);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<JsonNode> error(int status, String code, String message) {
        ObjectNode n = mapper.createObjectNode();
        n.put("code", code);
        n.put("message", message);
        return ResponseEntity.status(status).body(n);
    }

    static LocalDateTime parse(String ymdt) {
        return LocalDateTime.parse(ymdt, ShopbyMockState.YMDT);
    }
}
