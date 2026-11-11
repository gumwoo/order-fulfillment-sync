package io.github.gumwoo.fulfillment.core.port;

import io.github.gumwoo.fulfillment.core.order.OrderLineState;
import io.github.gumwoo.fulfillment.core.order.OrderStatusChange;
import java.time.LocalDateTime;
import java.util.Optional;

/** 상태 반영에 필요한 영속성 포트. 모든 메서드는 호출자의 트랜잭션 안에서 실행된다. */
public interface OrderSyncStore {

    /**
     * processed_event에 멱등 키를 넣는다 (UNIQUE). 이미 있으면 false (ADR-0001).
     */
    boolean markProcessed(String idempotencyKey, String source, LocalDateTime at);

    Optional<OrderLineState> findLine(long orderProductOptionNo);

    /**
     * 라인을 새로 만들거나 갱신한다. 기존 라인이면 expectedVersion이 맞아야 한다 (낙관적 락, ADR-0006).
     * 버전이 다르면 예외를 던져 트랜잭션을 되돌린다.
     */
    void saveLine(OrderStatusChange change, OrderLineState current);

    void recordHistory(OrderLineState before, OrderStatusChange change, String source, String idempotencyKey,
                       LocalDateTime at);

    void recordConflict(String targetKey, String reasonCode, String detail, LocalDateTime at);
}
