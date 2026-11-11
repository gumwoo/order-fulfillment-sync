package io.github.gumwoo.fulfillment.mock.shopby;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** mock 샵바이의 장애 주입 스위치와 호출 기록 (측정·검증용). */
@Component
public class ShopbyFaults {

    /** 송장 일괄 반영에서 한 번만 실패시킬 배송번호 */
    private final Set<Long> invoiceFailOnce = ConcurrentHashMap.newKeySet();
    /** 송장 일괄 반영 요청마다 담긴 배송번호 목록 */
    private final List<List<Long>> invoiceRequests = new ArrayList<>();
    /** 배송보류 호출마다 담긴 건수 */
    private final List<Integer> holdCalls = new ArrayList<>();
    private final Map<String, AtomicInteger> claimCalls = new ConcurrentHashMap<>();

    public synchronized void reset() {
        invoiceFailOnce.clear();
        invoiceRequests.clear();
        holdCalls.clear();
        claimCalls.clear();
    }

    public void failInvoiceOnce(Set<Long> shippingNos) {
        invoiceFailOnce.addAll(shippingNos);
    }

    boolean consumeInvoiceFailure(long shippingNo) {
        return invoiceFailOnce.remove(shippingNo);
    }

    synchronized void recordInvoiceRequest(JsonNode list) {
        List<Long> nos = new ArrayList<>();
        list.forEach(i -> nos.add(i.path("shippingNo").asLong()));
        invoiceRequests.add(nos);
    }

    synchronized void recordHoldCall(int size) {
        holdCalls.add(size);
    }

    void recordClaimCall(String kind, long claimNo) {
        claimCalls.computeIfAbsent(kind + ":" + claimNo, k -> new AtomicInteger()).incrementAndGet();
    }

    public synchronized List<List<Long>> invoiceRequests() {
        return new ArrayList<>(invoiceRequests);
    }

    public synchronized List<Integer> holdCalls() {
        return new ArrayList<>(holdCalls);
    }

    public Map<String, Integer> claimCalls() {
        Map<String, Integer> m = new ConcurrentHashMap<>();
        claimCalls.forEach((k, v) -> m.put(k, v.get()));
        return m;
    }

    public Set<Long> pendingInvoiceFailures() {
        return new HashSet<>(invoiceFailOnce);
    }
}
