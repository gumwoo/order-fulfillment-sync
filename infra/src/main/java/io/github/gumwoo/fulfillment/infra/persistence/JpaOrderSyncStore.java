package io.github.gumwoo.fulfillment.infra.persistence;

import io.github.gumwoo.fulfillment.core.order.OrderLineState;
import io.github.gumwoo.fulfillment.core.order.OrderStatusChange;
import io.github.gumwoo.fulfillment.core.port.OrderSyncStore;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

public class JpaOrderSyncStore implements OrderSyncStore {

    private final ProcessedEventRepository processedEvents;
    private final OrderLineStateRepository lines;
    private final StatusHistoryRepository history;
    private final ConflictRepository conflicts;

    public JpaOrderSyncStore(ProcessedEventRepository processedEvents, OrderLineStateRepository lines,
                             StatusHistoryRepository history, ConflictRepository conflicts) {
        this.processedEvents = processedEvents;
        this.lines = lines;
        this.history = history;
        this.conflicts = conflicts;
    }

    @Override
    public boolean markProcessed(String idempotencyKey, String source, LocalDateTime at) {
        return processedEvents.insertIgnore(idempotencyKey, source, at) == 1;
    }

    @Override
    public Optional<OrderLineState> findLine(long orderProductOptionNo) {
        return lines.findById(orderProductOptionNo).map(OrderLineStateEntity::toDomain);
    }

    @Override
    public void saveLine(OrderStatusChange change, OrderLineState current) {
        OrderLineStateEntity entity;
        if (current == null) {
            entity = new OrderLineStateEntity(change.orderProductOptionNo());
        } else {
            entity = lines.findById(change.orderProductOptionNo()).orElseThrow();
            if (entity.getVersion() != current.version()) {
                throw new ObjectOptimisticLockingFailureException(OrderLineStateEntity.class, change.orderProductOptionNo());
            }
        }
        entity.apply(change, LocalDateTime.now());
        // 새 라인을 두 소비자가 동시에 만들면 PK 충돌, 기존 라인이면 @Version 충돌로 커밋이 실패한다
        lines.saveAndFlush(entity);
    }

    @Override
    public void recordHistory(OrderLineState before, OrderStatusChange change, String source, String idempotencyKey,
                              LocalDateTime at) {
        history.save(new StatusHistoryEntity(change.orderProductOptionNo(),
                before == null ? null : before.orderStatus().name(), change.orderStatus().name(),
                change.claimStatusType(), source, idempotencyKey, at));
    }

    @Override
    public boolean applyAlreadyDelivery(long orderProductOptionNo, long claimNo, String deliveryCompanyType,
                                        String invoiceNo, LocalDateTime at) {
        String before = lines.findById(orderProductOptionNo).map(l -> l.getStatus().name()).orElse(null);
        if (lines.applyAlreadyDelivery(orderProductOptionNo, claimNo, deliveryCompanyType, invoiceNo, at) == 0) {
            return false;
        }
        String after = io.github.gumwoo.fulfillment.core.order.OrderStatus.valueOf(before).isBeforeShipment()
                ? io.github.gumwoo.fulfillment.core.order.OrderStatus.DELIVERY_ING.name() : before;
        history.save(new StatusHistoryEntity(orderProductOptionNo, before, after, null, "ALREADY_DELIVERY",
                "already-delivery:" + claimNo, at));
        return true;
    }

    @Override
    public void recordConflict(String targetKey, String reasonCode, String detail, LocalDateTime at) {
        conflicts.save(new ConflictEntity(targetKey, reasonCode, detail, at));
    }
}
