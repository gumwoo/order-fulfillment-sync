package io.github.gumwoo.fulfillment.core.shipment;

import io.github.gumwoo.fulfillment.core.port.AlertStore;
import io.github.gumwoo.fulfillment.core.port.ConflictRecorder;
import io.github.gumwoo.fulfillment.core.port.InvoiceStore;
import io.github.gumwoo.fulfillment.core.port.InvoiceStore.PendingInvoice;
import io.github.gumwoo.fulfillment.core.port.ShopbyClient;
import io.github.gumwoo.fulfillment.core.port.ShopbyClient.InvoiceChange;
import io.github.gumwoo.fulfillment.core.port.ShopbyClient.InvoiceChangeResult;
import io.github.gumwoo.fulfillment.core.port.TransactionRunner;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 송장 일괄 반영 (설계 4.6, F-11): 보낼 배송을 {@code PUT /orders/change-status/by-shipping-no}로 100건씩 보낸다.
 * 이 API는 건별로 부분 실패하므로(external-facts.md) 응답 failures의 배송번호만 미반영으로 두고 나머지는 반영 완료로 표시한다.
 * <ul>
 *   <li>대상 배송을 잠근 채 보내고 기록한다. 인스턴스가 여러 대여도 같은 배송을 동시에 보내지 않는다.</li>
 *   <li>응답이 요청과 맞지 않으면(실패 수 불일치, 보내지 않은 배송번호) 전부 실패로 보고 다시 보낸다.</li>
 *   <li>실패로 돌아온 배송이 이미 샵바이에 반영돼 있으면(우리 주문 라인이 배송중이고 송장이 같으면) 반영 완료로 본다.
 *       앞선 요청이 반영됐는데 응답을 잃으면 다시 보낸 요청이 "이미 배송중"으로 거절되기 때문이다.</li>
 *   <li>같은 배송이 {@value #MAX_FAILURES}번 실패하면(예: 취소·배송보류로 거절) conflict와 경보를 남기고 더 보내지 않는다.</li>
 * </ul>
 */
public class InvoiceSyncService {

    public static final int BATCH = 100;
    public static final int MAX_FAILURES = 5;
    public static final String CONFLICT_INVOICE_SYNC_FAILED = "INVOICE_SYNC_FAILED";
    static final String TARGET_STATUS = "DELIVERY_ING";

    private final InvoiceStore store;
    private final ShopbyClient shopby;
    private final ConflictRecorder conflicts;
    private final AlertStore alerts;
    private final TransactionRunner tx;
    private final Clock clock;

    public InvoiceSyncService(InvoiceStore store, ShopbyClient shopby, ConflictRecorder conflicts, AlertStore alerts,
                              TransactionRunner tx, Clock clock) {
        this.store = store;
        this.shopby = shopby;
        this.conflicts = conflicts;
        this.alerts = alerts;
        this.tx = tx;
        this.clock = clock;
    }

    public record Result(int sent, int succeeded, int failed) {
    }

    /** 한 번 실행에 최대 BATCH건(요청 1회). 샵바이 RateLimitedException은 그대로 나가고 아무것도 표시하지 않는다. */
    public Result syncPending() {
        return tx.inTransaction(() -> {
            List<PendingInvoice> pending = store.lockPending(BATCH, MAX_FAILURES);
            if (pending.isEmpty()) {
                return new Result(0, 0, 0);
            }
            List<InvoiceChange> changes = pending.stream()
                    .map(p -> new InvoiceChange(p.shippingNo(), p.deliveryCompanyType(), p.invoiceNo())).toList();
            InvoiceChangeResult result = shopby.changeStatusByShippingNo(TARGET_STATUS, changes);
            Set<Long> sent = pending.stream().map(PendingInvoice::shippingNo).collect(Collectors.toSet());
            Set<Long> failed = new HashSet<>();
            result.failures().forEach(f -> failed.add(f.originalRequest().shippingNo()));
            boolean consistent = sent.containsAll(failed) && result.failCount() == failed.size()
                    && result.successCount() + result.failCount() == pending.size();
            if (!consistent) {
                // 어느 건이 실패했는지 믿을 수 없다: 전부 실패로 보고 다시 보낸다 (성공한 건은 다음 요청에서 거절돼 실패로 쌓인다)
                failed.addAll(sent);
            }
            LocalDateTime now = LocalDateTime.now(clock);
            for (PendingInvoice p : pending) {
                if (failed.contains(p.shippingNo()) && store.alreadyApplied(p.shippingNo(), p.invoiceNo())) {
                    failed.remove(p.shippingNo());
                }
                if (failed.contains(p.shippingNo())) {
                    int n = store.markSyncFailed(p.shippingNo(), now);
                    if (n >= MAX_FAILURES) {
                        String key = "shipping:" + p.shippingNo();
                        if (conflicts.recordIfAbsent(key, CONFLICT_INVOICE_SYNC_FAILED,
                                "송장 반영 " + n + "회 실패 (취소·배송보류 등으로 거절된 배송일 수 있음)", now)) {
                            alerts.add(CONFLICT_INVOICE_SYNC_FAILED, key, "송장 반영 " + n + "회 실패", now);
                        }
                    }
                } else {
                    store.markSynced(p.shippingNo(), now);
                }
            }
            int failedCount = (int) pending.stream().filter(p -> failed.contains(p.shippingNo())).count();
            return new Result(pending.size(), pending.size() - failedCount, failedCount);
        });
    }
}
