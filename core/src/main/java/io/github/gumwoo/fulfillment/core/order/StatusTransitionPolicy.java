package io.github.gumwoo.fulfillment.core.order;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 도착 순서 대신 상태 우선순위로 반영 여부를 정한다 (ADR-0003).
 * <ul>
 *   <li>주문 상태가 앞선 단계면 반영</li>
 *   <li>같은 단계면 단계별 시각이 더 늦을 때, 또는 클레임 정보가 바뀌었을 때 반영</li>
 *   <li>뒤로 가는 전이는 반영하지 않고 conflict</li>
 * </ul>
 * 클레임(취소 등)은 별도 축이다. 주문 상태가 같은 단계이고 클레임만 바뀐 변경은 반영한다.
 */
public final class StatusTransitionPolicy {

    public enum Decision {
        /** 처음 보는 라인이거나 앞선 변경: 반영 */
        APPLY,
        /** 같은 단계의 같거나 오래된 변경: 무시 */
        STALE,
        /** 뒤로 가는 전이: 반영하지 않고 conflict로 남긴다 */
        CONFLICT
    }

    private StatusTransitionPolicy() {
    }

    public static Decision decide(OrderLineState current, OrderStatusChange incoming) {
        if (current == null) {
            return Decision.APPLY;
        }
        OrderStatus now = current.orderStatus();
        OrderStatus next = incoming.orderStatus();
        if (next.isAfter(now)) {
            return Decision.APPLY;
        }
        if (now.isAfter(next)) {
            return Decision.CONFLICT;
        }
        if (claimChanged(current, incoming)) {
            return Decision.APPLY;
        }
        LocalDateTime curTime = current.latestStageTime();
        LocalDateTime newTime = incoming.stageTimes().latest();
        if (newTime != null && (curTime == null || newTime.isAfter(curTime))) {
            return Decision.APPLY;
        }
        return Decision.STALE;
    }

    private static boolean claimChanged(OrderLineState current, OrderStatusChange incoming) {
        if (!incoming.hasClaim()) {
            // 클레임 정보가 빠진 변경으로 기존 클레임을 지우지 않는다
            return false;
        }
        return !Objects.equals(current.claimNo(), incoming.claimNo())
                || ClaimStatus.rank(incoming.claimStatusType()) > ClaimStatus.rank(current.claimStatusType());
    }
}
