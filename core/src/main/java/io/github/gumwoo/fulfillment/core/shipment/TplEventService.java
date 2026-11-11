package io.github.gumwoo.fulfillment.core.shipment;

import io.github.gumwoo.fulfillment.core.port.AlertStore;
import io.github.gumwoo.fulfillment.core.port.ConflictRecorder;
import io.github.gumwoo.fulfillment.core.port.InvoiceStore;
import io.github.gumwoo.fulfillment.core.port.InvoiceStore.ShipmentView;
import io.github.gumwoo.fulfillment.core.port.TplClient.Event;
import io.github.gumwoo.fulfillment.core.port.TplClient.Status;
import io.github.gumwoo.fulfillment.core.port.TransactionRunner;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 3PL 출고 이벤트 반영 (설계 4.7, 4.8).
 * <ul>
 *   <li>INVOICE_ISSUED: 송장 저장</li>
 *   <li>STATUS_CHANGED: 3PL 상태 저장. 출고완료(RELEASED)인데 송장이 아직 없으면 지연 재처리 대상이다(F-12).
 *       호출자가 메시지를 지연 재발행하고, 최대 횟수를 넘으면 {@link #giveUpRelease}로 conflict와 경보를 남긴다.</li>
 * </ul>
 * 출고완료·송장 있음이 되면 송장 반영 작업(InvoiceSyncService)이 샵바이에 보낸다.
 */
public class TplEventService {

    public static final String CONFLICT_RELEASE_WITHOUT_INVOICE = "RELEASE_WITHOUT_INVOICE";

    public enum Result {
        /** 반영 끝 */
        APPLIED,
        /** 출고완료인데 송장이 없다: 나중에 다시 */
        WAIT_FOR_INVOICE,
        /** 우리가 모르는 출고 (다른 경로로 들어온 3PL 주문) */
        UNKNOWN_SHIPMENT
    }

    private final InvoiceStore store;
    private final ConflictRecorder conflicts;
    private final AlertStore alerts;
    private final TransactionRunner tx;
    private final Clock clock;

    public TplEventService(InvoiceStore store, ConflictRecorder conflicts, AlertStore alerts, TransactionRunner tx,
                           Clock clock) {
        this.store = store;
        this.conflicts = conflicts;
        this.alerts = alerts;
        this.tx = tx;
        this.clock = clock;
    }

    public Result apply(Event e) {
        LocalDateTime now = LocalDateTime.now(clock);
        return tx.inTransaction(() -> {
            ShipmentView s = store.find(e.orderProductOptionNo()).orElse(null);
            if (s == null) {
                return Result.UNKNOWN_SHIPMENT;
            }
            if ("INVOICE_ISSUED".equals(e.type())) {
                if (s.tplStatus() == Status.CANCELED) {
                    return Result.APPLIED;
                }
                store.saveInvoice(e.orderProductOptionNo(), e.invoiceNo(), e.deliveryCompanyType(), now);
                return Result.APPLIED;
            }
            if (e.status() != null) {
                // 저장소가 앞 단계로만 바꾼다 (늦게 처리된 이벤트가 되돌리지 않음)
                store.updateTplStatus(e.orderProductOptionNo(), e.status(), now);
            }
            if (e.status() == Status.RELEASED && s.invoiceNo() == null) {
                return Result.WAIT_FOR_INVOICE;
            }
            return Result.APPLIED;
        });
    }

    /** 지연 재처리 최대 횟수를 넘었다: 사람이 보게 conflict와 경보(묶음 알림 대상)를 남긴다 */
    public void giveUpRelease(long orderProductOptionNo, int attempts) {
        LocalDateTime now = LocalDateTime.now(clock);
        tx.inTransaction(() -> {
            String detail = "출고완료 후 송장이 " + attempts + "회 재확인에도 없음";
            // 같은 이벤트가 두 번 들어와도(수집 중 재시작 등) conflict와 경보는 한 번
            if (conflicts.recordIfAbsent(String.valueOf(orderProductOptionNo), CONFLICT_RELEASE_WITHOUT_INVOICE, detail, now)) {
                alerts.add(CONFLICT_RELEASE_WITHOUT_INVOICE, String.valueOf(orderProductOptionNo), detail, now);
            }
            return null;
        });
    }
}
