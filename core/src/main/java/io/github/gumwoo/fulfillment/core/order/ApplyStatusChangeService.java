package io.github.gumwoo.fulfillment.core.order;

import io.github.gumwoo.fulfillment.core.order.StatusTransitionPolicy.Decision;
import io.github.gumwoo.fulfillment.core.port.OrderSyncStore;
import io.github.gumwoo.fulfillment.core.port.TransactionRunner;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 상태 변경 하나를 반영한다. 한 트랜잭션 안에서:
 * 멱등 키 기록(중복이면 끝) → 현재 상태 조회 → 우선순위 판정 → 반영+이력 또는 conflict 기록.
 * 낙관적 락 충돌이면 예외로 전체를 되돌리고(멱등 키 기록 포함), 호출자가 다시 시도한다.
 */
public class ApplyStatusChangeService {

    public static final String CONFLICT_BACKWARD_TRANSITION = "BACKWARD_TRANSITION";

    public enum Outcome {
        APPLIED, DUPLICATE, STALE, CONFLICT
    }

    private final OrderSyncStore store;
    private final TransactionRunner tx;
    private final Clock clock;

    public ApplyStatusChangeService(OrderSyncStore store, TransactionRunner tx, Clock clock) {
        this.store = store;
        this.tx = tx;
        this.clock = clock;
    }

    public Outcome apply(OrderStatusChange change, String source) {
        String key = change.idempotencyKey();
        return tx.inTransaction(() -> {
            LocalDateTime now = LocalDateTime.now(clock);
            if (!store.markProcessed(key, source, now)) {
                return Outcome.DUPLICATE;
            }
            OrderLineState current = store.findLine(change.orderProductOptionNo()).orElse(null);
            Decision decision = StatusTransitionPolicy.decide(current, change);
            switch (decision) {
                case APPLY -> {
                    store.saveLine(change, current);
                    store.recordHistory(current, change, source, key, now);
                    return Outcome.APPLIED;
                }
                case CONFLICT -> {
                    store.recordConflict(String.valueOf(change.orderProductOptionNo()), CONFLICT_BACKWARD_TRANSITION,
                            current.orderStatus() + " -> " + change.orderStatus() + " (source=" + source + ")", now);
                    return Outcome.CONFLICT;
                }
                default -> {
                    return Outcome.STALE;
                }
            }
        });
    }
}
