package io.github.gumwoo.fulfillment.core.shipment;

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

/**
 * 송장 일괄 반영 (설계 4.6, F-11): 출고완료·송장 있음·미반영 배송을 {@code PUT /orders/change-status/by-shipping-no}로
 * 100건씩 보낸다. 이 API는 건별로 부분 실패하므로(external-facts.md), 응답의 failures에 든 배송번호만 미반영으로 두고
 * 나머지는 반영 완료로 표시한다. 다음 실행은 미반영 건만 다시 보낸다.
 */
public class InvoiceSyncService {

    public static final int BATCH = 100;
    static final String TARGET_STATUS = "DELIVERY_ING";

    private final InvoiceStore store;
    private final ShopbyClient shopby;
    private final TransactionRunner tx;
    private final Clock clock;

    public InvoiceSyncService(InvoiceStore store, ShopbyClient shopby, TransactionRunner tx, Clock clock) {
        this.store = store;
        this.shopby = shopby;
        this.tx = tx;
        this.clock = clock;
    }

    public record Result(int sent, int succeeded, int failed) {
    }

    /** 한 번 실행에 최대 BATCH건(요청 1회). 샵바이 RateLimitedException은 그대로 나간다 (다음 실행에 같은 건을 다시 보낸다). */
    public Result syncPending() {
        List<PendingInvoice> pending = tx.inTransaction(() -> store.findPending(BATCH));
        if (pending.isEmpty()) {
            return new Result(0, 0, 0);
        }
        List<InvoiceChange> changes = pending.stream()
                .map(p -> new InvoiceChange(p.shippingNo(), p.deliveryCompanyType(), p.invoiceNo())).toList();
        InvoiceChangeResult result = shopby.changeStatusByShippingNo(TARGET_STATUS, changes);
        Set<Long> failed = new HashSet<>();
        result.failures().forEach(f -> failed.add(f.originalRequest().shippingNo()));
        LocalDateTime now = LocalDateTime.now(clock);
        tx.inTransaction(() -> {
            for (PendingInvoice p : pending) {
                if (failed.contains(p.shippingNo())) {
                    store.markSyncFailed(p.shippingNo(), now);
                } else {
                    store.markSynced(p.shippingNo(), now);
                }
            }
            return null;
        });
        return new Result(pending.size(), pending.size() - failed.size(), failed.size());
    }
}
