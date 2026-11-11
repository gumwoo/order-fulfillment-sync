package io.github.gumwoo.fulfillment.infra.persistence;

import io.github.gumwoo.fulfillment.core.port.AlertStore;
import io.github.gumwoo.fulfillment.core.port.InvoiceStore;
import io.github.gumwoo.fulfillment.core.port.TplClient.Status;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Limit;

public class JpaInvoiceStore implements InvoiceStore, AlertStore {

    public static final String CONFLICT_INVOICE_MISMATCH = "INVOICE_MISMATCH_IN_DELIVERY";

    private final ShipmentRepository shipments;
    private final OrderLineStateRepository lines;
    private final TplCursorRepository cursors;
    private final AlertRepository alerts;
    private final NotificationRepository notifications;
    private final io.github.gumwoo.fulfillment.core.port.ConflictRecorder conflicts;

    public JpaInvoiceStore(ShipmentRepository shipments, OrderLineStateRepository lines, TplCursorRepository cursors,
                           AlertRepository alerts, NotificationRepository notifications,
                           io.github.gumwoo.fulfillment.core.port.ConflictRecorder conflicts) {
        this.shipments = shipments;
        this.lines = lines;
        this.cursors = cursors;
        this.alerts = alerts;
        this.notifications = notifications;
        this.conflicts = conflicts;
    }

    @Override
    public Optional<ShipmentView> find(long optionNo) {
        return shipments.findById(optionNo)
                .map(s -> new ShipmentView(s.getOrderProductOptionNo(), s.getDeliveryNo(), s.getTplStatus(), s.getInvoiceNo()));
    }

    @Override
    public void updateTplStatus(long optionNo, Status status, LocalDateTime at) {
        shipments.findById(optionNo).ifPresent(s -> s.tplStatus(status, at));
    }

    @Override
    public void saveInvoice(long optionNo, String invoiceNo, String company, LocalDateTime at) {
        shipments.findById(optionNo).ifPresent(s -> s.invoice(invoiceNo, company, at));
    }

    /**
     * by-shipping-no는 배송번호 기준으로 처리된다(스펙). 배송의 모든 옵션이 준비됐을 때만 보낸다:
     * 우리가 아는 그 배송의 주문 라인(취소 완료 제외)마다 출고 행이 있고, 모두 출고완료이며, 송장이 하나로 같아야 한다.
     */
    @Override
    public List<PendingInvoice> lockPending(int limit, int maxFailures) {
        List<PendingInvoice> result = new ArrayList<>();
        for (Long deliveryNo : shipments.findCandidateDeliveryNos(maxFailures, limit * 3)) {
            if (result.size() >= limit) {
                break;
            }
            List<ShipmentEntity> locked = shipments.lockByDeliveryNo(deliveryNo);
            Map<Long, ShipmentEntity> byOption = new LinkedHashMap<>();
            locked.forEach(s -> byOption.put(s.getOrderProductOptionNo(), s));
            List<OrderLineStateEntity> deliveryLines = lines.findByDeliveryNo(deliveryNo).stream()
                    .filter(l -> !io.github.gumwoo.fulfillment.core.order.ClaimStatus.isCancelDone(l.toDomain().claimStatusType()))
                    .toList();
            boolean ready = !deliveryLines.isEmpty() && deliveryLines.stream().allMatch(l -> {
                ShipmentEntity s = byOption.get(l.getOrderProductOptionNo());
                return s != null && s.getTplStatus() == Status.RELEASED && s.getInvoiceNo() != null
                        && s.getInvoiceSyncedAt() == null;
            });
            if (!ready) {
                continue;
            }
            java.util.Set<String> invoices = deliveryLines.stream()
                    .map(l -> byOption.get(l.getOrderProductOptionNo()))
                    .map(s -> s.getDeliveryCompanyType() + "|" + s.getInvoiceNo())
                    .collect(java.util.stream.Collectors.toSet());
            if (invoices.size() > 1) {
                conflicts.recordIfAbsent("shipping:" + deliveryNo, CONFLICT_INVOICE_MISMATCH,
                        "한 배송의 옵션별 송장이 다름: " + invoices, LocalDateTime.now());
                continue;
            }
            ShipmentEntity first = byOption.get(deliveryLines.get(0).getOrderProductOptionNo());
            result.add(new PendingInvoice(deliveryNo, first.getDeliveryCompanyType(), first.getInvoiceNo()));
        }
        return result;
    }

    @Override
    public void markSynced(long shippingNo, LocalDateTime at) {
        shipments.findByDeliveryNo(shippingNo).forEach(s -> s.invoiceSynced(at));
    }

    @Override
    public int markSyncFailed(long shippingNo, LocalDateTime at) {
        int max = 0;
        for (ShipmentEntity s : shipments.findByDeliveryNo(shippingNo)) {
            max = Math.max(max, s.increaseSyncFailures(at));
        }
        return max;
    }

    @Override
    public long getCursor(String name) {
        return cursors.findById(name).map(TplCursorEntity::getLastSeq).orElse(0L);
    }

    @Override
    public void saveCursor(String name, long seq) {
        TplCursorEntity c = cursors.findById(name).orElseGet(() -> new TplCursorEntity(name, 0));
        c.setLastSeq(seq);
        cursors.save(c);
    }

    // ---- AlertStore ----

    @Override
    public void add(String type, String targetKey, String message, LocalDateTime at) {
        alerts.save(new AlertEntity(type, targetKey, message, at));
    }

    @Override
    public List<Alert> findUnnotified() {
        return alerts.findByNotifiedAtIsNullOrderByIdAsc().stream()
                .map(a -> new Alert(a.getId(), a.getType(), a.getTargetKey(), a.getMessage(), a.getCreatedAt())).toList();
    }

    @Override
    public long createNotification(String text, List<Long> alertIds, LocalDateTime at) {
        NotificationEntity n = notifications.save(new NotificationEntity("SLACK", text, alertIds.size(), at));
        alerts.findAllById(alertIds).forEach(a -> a.markNotified(n.getId(), at));
        return n.getId();
    }
}
