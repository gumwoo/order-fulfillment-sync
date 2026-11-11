package io.github.gumwoo.fulfillment.mock.tpl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * 파스토 규칙을 따르는 3PL mock 상태 (external-facts.md 파스토 절).
 * <ul>
 *   <li>출고 단계: 출고요청(REQUESTED) → 출고작업(WORKING) → 피킹(PICKED) → 출고완료(RELEASED)</li>
 *   <li>취소: 출고요청이면 무료, 출고작업(피킹 전)이면 수수료 1,000원, 피킹 이후 불가</li>
 *   <li>옵션명 매핑: 매핑 검사를 켜면 매핑표에 없는 옵션은 수집 실패(미연동)</li>
 * </ul>
 * API 형태(경로, 필드)는 이 프로젝트가 정한 것이다. 실제 파스토 API가 아니다.
 */
@Component
public class TplMockState {

    public static final int CANCEL_FEE_WORKING = 1000;

    public static final class Order {
        long orderProductOptionNo;
        String orderNo;
        Long deliveryNo;
        String productName;
        String optionName;
        String optionValue;
        int quantity;
        String status;
        String invoiceNo;
        String deliveryCompanyType;
        int cancelFee;
    }

    public record Event(long seq, String type, long orderProductOptionNo, String status, String invoiceNo,
                       String deliveryCompanyType) {
    }

    private final Map<Long, Order> orders = new ConcurrentHashMap<>();
    private final List<Event> events = new ArrayList<>();
    private final Set<String> mapping = new HashSet<>();
    private volatile boolean enforceMapping = false;
    private final Map<Long, AtomicInteger> registerCalls = new ConcurrentHashMap<>();
    private final Map<Long, AtomicInteger> cancelCalls = new ConcurrentHashMap<>();
    private long seq = 0;

    public synchronized void reset() {
        orders.clear();
        events.clear();
        mapping.clear();
        enforceMapping = false;
        registerCalls.clear();
        cancelCalls.clear();
        seq = 0;
    }

    public synchronized void configureMapping(boolean enforce, List<String> allowed) {
        this.enforceMapping = enforce;
        mapping.clear();
        mapping.addAll(allowed);
    }

    public synchronized void allowMapping(String key) {
        mapping.add(key);
    }

    static String mappingKey(String optionName, String optionValue) {
        return optionName + "|" + optionValue;
    }

    /** @return null이면 매핑 실패 */
    public synchronized Order register(Order req) {
        registerCalls.computeIfAbsent(req.orderProductOptionNo, k -> new AtomicInteger()).incrementAndGet();
        Order existing = orders.get(req.orderProductOptionNo);
        if (existing != null) {
            return existing;
        }
        if (enforceMapping && !mapping.contains(mappingKey(req.optionName, req.optionValue))) {
            return null;
        }
        req.status = "REQUESTED";
        orders.put(req.orderProductOptionNo, req);
        emit("STATUS_CHANGED", req);
        return req;
    }

    public Order find(long no) {
        return orders.get(no);
    }

    /** @return 취소된 주문, 또는 취소할 수 없으면 null */
    public synchronized Order cancel(long no) {
        cancelCalls.computeIfAbsent(no, k -> new AtomicInteger()).incrementAndGet();
        Order o = orders.get(no);
        if (o == null) {
            return null;
        }
        switch (o.status) {
            case "REQUESTED" -> o.cancelFee = 0;
            case "WORKING" -> o.cancelFee = CANCEL_FEE_WORKING;
            case "CANCELED" -> {
                return o;
            }
            default -> {
                return null;
            }
        }
        o.status = "CANCELED";
        emit("STATUS_CHANGED", o);
        return o;
    }

    public synchronized void advance(long no, String status) {
        Order o = orders.get(no);
        if (o == null) {
            throw new IllegalArgumentException("없는 주문: " + no);
        }
        o.status = status;
        emit("STATUS_CHANGED", o);
    }

    public synchronized void issueInvoice(long no, String invoiceNo, String company) {
        Order o = orders.get(no);
        if (o == null) {
            throw new IllegalArgumentException("없는 주문: " + no);
        }
        o.invoiceNo = invoiceNo;
        o.deliveryCompanyType = company;
        emit("INVOICE_ISSUED", o);
    }

    private void emit(String type, Order o) {
        events.add(new Event(++seq, type, o.orderProductOptionNo, o.status, o.invoiceNo, o.deliveryCompanyType));
    }

    public synchronized List<Event> eventsAfter(long afterSeq, int limit) {
        return events.stream().filter(e -> e.seq() > afterSeq).limit(limit).toList();
    }

    public synchronized long lastSeq() {
        return seq;
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<Long, Integer> reg = new LinkedHashMap<>();
        registerCalls.forEach((k, v) -> reg.put(k, v.get()));
        Map<Long, Integer> can = new LinkedHashMap<>();
        cancelCalls.forEach((k, v) -> can.put(k, v.get()));
        m.put("orders", orders.size());
        m.put("registerCalls", reg);
        m.put("cancelCalls", can);
        return m;
    }
}
