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
    /** 배송보류 요청에 이 옵션이 들어 있으면 한 번만 실패시킨다 (일괄 실패 → 건별 재시도 검증) */
    private final Set<Long> holdFailOnce = ConcurrentHashMap.newKeySet();
    /** 송장 일괄 반영 요청마다 담긴 배송번호 목록 */
    private final List<List<Long>> invoiceRequests = new ArrayList<>();
    /** 배송보류 호출마다 담긴 건수 */
    private final List<Integer> holdCalls = new ArrayList<>();
    private final Map<String, AtomicInteger> claimCalls = new ConcurrentHashMap<>();
    /** 송장 일괄 반영을 처리한 뒤 응답을 읽을 수 없게 망가뜨릴 횟수 ("반영됐는데 응답을 잃음" 흉내) */
    private final AtomicInteger loseInvoiceResponses = new AtomicInteger();

    public synchronized void reset() {
        invoiceFailOnce.clear();
        holdFailOnce.clear();
        invoiceRequests.clear();
        holdCalls.clear();
        claimCalls.clear();
        loseInvoiceResponses.set(0);
    }

    public void loseInvoiceResponseOnce() {
        loseInvoiceResponses.incrementAndGet();
    }

    boolean consumeInvoiceResponseLoss() {
        return loseInvoiceResponses.getAndUpdate(v -> Math.max(0, v - 1)) > 0;
    }

    public void failInvoiceOnce(Set<Long> shippingNos) {
        invoiceFailOnce.addAll(shippingNos);
    }

    public void failHoldOnce(Set<Long> optionNos) {
        holdFailOnce.addAll(optionNos);
    }

    /** 요청에 들어 있는 실패 대상을 소비한다. 하나라도 있으면 true */
    boolean consumeHoldFailure(java.util.Collection<Long> optionNos) {
        boolean hit = false;
        for (Long no : optionNos) {
            hit |= holdFailOnce.remove(no);
        }
        return hit;
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
