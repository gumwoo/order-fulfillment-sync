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

    private final ShipmentRepository shipments;
    private final TplCursorRepository cursors;
    private final AlertRepository alerts;
    private final NotificationRepository notifications;

    public JpaInvoiceStore(ShipmentRepository shipments, TplCursorRepository cursors, AlertRepository alerts,
                           NotificationRepository notifications) {
        this.shipments = shipments;
        this.cursors = cursors;
        this.alerts = alerts;
        this.notifications = notifications;
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

    @Override
    public List<PendingInvoice> findPending(int limit) {
        // 배송번호 하나에 옵션이 여러 개일 수 있다: 배송번호로 묶는다
        Map<Long, PendingInvoice> byShipping = new LinkedHashMap<>();
        for (ShipmentEntity s : shipments.findReleasedInvoicePending(Limit.of(limit * 10))) {
            if (byShipping.size() >= limit && !byShipping.containsKey(s.getDeliveryNo())) {
                break;
            }
            byShipping.putIfAbsent(s.getDeliveryNo(),
                    new PendingInvoice(s.getDeliveryNo(), s.getDeliveryCompanyType(), s.getInvoiceNo()));
        }
        return new ArrayList<>(byShipping.values());
    }

    @Override
    public void markSynced(long shippingNo, LocalDateTime at) {
        shipments.findByDeliveryNo(shippingNo).forEach(s -> s.invoiceSynced(at));
    }

    @Override
    public void markSyncFailed(long shippingNo, LocalDateTime at) {
        shipments.findByDeliveryNo(shippingNo).forEach(s -> s.invoiceSyncFailed(at));
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
