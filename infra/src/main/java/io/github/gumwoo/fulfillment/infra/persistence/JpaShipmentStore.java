package io.github.gumwoo.fulfillment.infra.persistence;

import io.github.gumwoo.fulfillment.core.port.ShipmentStore;
import io.github.gumwoo.fulfillment.core.port.TplClient.Status;
import java.time.LocalDateTime;
import java.util.Optional;

public class JpaShipmentStore implements ShipmentStore {

    private final ShipmentRepository shipments;
    private final UnlinkedRepository unlinked;

    public JpaShipmentStore(ShipmentRepository shipments, UnlinkedRepository unlinked) {
        this.shipments = shipments;
        this.unlinked = unlinked;
    }

    @Override
    public Optional<Shipment> find(long orderProductOptionNo) {
        return shipments.findById(orderProductOptionNo).map(ShipmentEntity::toDomain);
    }

    @Override
    public void markRegistered(long orderProductOptionNo, Long deliveryNo, Status status, LocalDateTime at) {
        ShipmentEntity e = row(orderProductOptionNo, at);
        e.register(deliveryNo, status, at);
        shipments.saveAndFlush(e);
    }

    @Override
    public void markPrepared(long orderProductOptionNo, LocalDateTime at) {
        ShipmentEntity e = row(orderProductOptionNo, at);
        e.prepared(at);
        shipments.saveAndFlush(e);
    }

    @Override
    public void updateTplStatus(long orderProductOptionNo, Status status, LocalDateTime at) {
        ShipmentEntity e = row(orderProductOptionNo, at);
        e.tplStatus(status, at);
        shipments.saveAndFlush(e);
    }

    /** 행이 없으면 INSERT IGNORE로 만들고 읽는다 */
    private ShipmentEntity row(long orderProductOptionNo, LocalDateTime at) {
        shipments.insertIgnore(orderProductOptionNo, at);
        return shipments.findById(orderProductOptionNo).orElseThrow();
    }

    @Override
    public void recordUnlinkedIfAbsent(long orderProductOptionNo, String reasonCode, String detail, LocalDateTime at) {
        unlinked.insertOpenIgnore(orderProductOptionNo, reasonCode, detail, at);
    }

    @Override
    public void resolveUnlinked(long orderProductOptionNo, String resolver, LocalDateTime at) {
        unlinked.findFirstByOrderProductOptionNoAndResolvedAtIsNull(orderProductOptionNo)
                .ifPresent(u -> u.resolve(resolver, at));
    }
}
