package io.github.gumwoo.fulfillment.core.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gumwoo.fulfillment.core.order.OrderStatusChange.StageTimes;
import io.github.gumwoo.fulfillment.core.order.StatusTransitionPolicy.Decision;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class StatusTransitionPolicyTest {

    static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 11, 10, 0, 0);

    @Test
    void 처음_보는_라인은_반영한다() {
        assertThat(StatusTransitionPolicy.decide(null, change(OrderStatus.PAY_DONE, T0, null, null)))
                .isEqualTo(Decision.APPLY);
    }

    @Test
    void 앞선_단계는_반영한다() {
        OrderLineState cur = state(OrderStatus.PAY_DONE, T0, null, null);
        assertThat(StatusTransitionPolicy.decide(cur, change(OrderStatus.DELIVERY_ING, T0.plusHours(1), null, null)))
                .isEqualTo(Decision.APPLY);
    }

    @Test
    void 뒤로_가는_전이는_conflict다() {
        OrderLineState cur = state(OrderStatus.DELIVERY_ING, T0.plusHours(1), null, null);
        assertThat(StatusTransitionPolicy.decide(cur, change(OrderStatus.PAY_DONE, T0, null, null)))
                .isEqualTo(Decision.CONFLICT);
    }

    @Test
    void 같은_단계는_단계_시각이_더_늦을_때만_반영한다() {
        OrderLineState cur = state(OrderStatus.PAY_DONE, T0, null, null);
        assertThat(StatusTransitionPolicy.decide(cur, change(OrderStatus.PAY_DONE, T0, null, null)))
                .isEqualTo(Decision.STALE);
        assertThat(StatusTransitionPolicy.decide(cur, change(OrderStatus.PAY_DONE, T0.minusMinutes(1), null, null)))
                .isEqualTo(Decision.STALE);
        assertThat(StatusTransitionPolicy.decide(cur, change(OrderStatus.PAY_DONE, T0.plusMinutes(1), null, null)))
                .isEqualTo(Decision.APPLY);
    }

    @Test
    void 같은_단계에서_클레임이_진행되면_반영하고_되돌아가면_무시한다() {
        OrderLineState none = state(OrderStatus.PRODUCT_PREPARE, T0, null, null);
        assertThat(StatusTransitionPolicy.decide(none, change(OrderStatus.PRODUCT_PREPARE, T0, "CANCEL_REQUEST", 9L)))
                .isEqualTo(Decision.APPLY);

        OrderLineState requested = state(OrderStatus.PRODUCT_PREPARE, T0, "CANCEL_REQUEST", 9L);
        assertThat(StatusTransitionPolicy.decide(requested, change(OrderStatus.PRODUCT_PREPARE, T0, "CANCEL_DONE", 9L)))
                .isEqualTo(Decision.APPLY);

        OrderLineState done = state(OrderStatus.PRODUCT_PREPARE, T0, "CANCEL_DONE", 9L);
        assertThat(StatusTransitionPolicy.decide(done, change(OrderStatus.PRODUCT_PREPARE, T0, "CANCEL_REQUEST", 9L)))
                .isEqualTo(Decision.STALE);
    }

    @Test
    void 클레임이_빠진_변경으로_기존_클레임을_지우지_않는다() {
        OrderLineState requested = state(OrderStatus.PRODUCT_PREPARE, T0, "CANCEL_REQUEST", 9L);
        assertThat(StatusTransitionPolicy.decide(requested, change(OrderStatus.PRODUCT_PREPARE, T0, null, null)))
                .isEqualTo(Decision.STALE);
    }

    @Test
    void 멱등_키는_경로와_무관하고_상태_클레임_단계시각이_다르면_달라진다() {
        OrderStatusChange a = change(OrderStatus.PAY_DONE, T0, null, null);
        OrderStatusChange same = change(OrderStatus.PAY_DONE, T0, null, null);
        assertThat(a.idempotencyKey()).isEqualTo(same.idempotencyKey()).hasSize(64);
        assertThat(a.idempotencyKey()).isNotEqualTo(change(OrderStatus.PRODUCT_PREPARE, T0, null, null).idempotencyKey());
        assertThat(a.idempotencyKey()).isNotEqualTo(change(OrderStatus.PAY_DONE, T0.plusSeconds(1), null, null).idempotencyKey());
        assertThat(a.idempotencyKey()).isNotEqualTo(change(OrderStatus.PAY_DONE, T0, "CANCEL_REQUEST", 1L).idempotencyKey());
    }

    static OrderStatusChange change(OrderStatus status, LocalDateTime latest, String claim, Long claimNo) {
        return new OrderStatusChange(1L, "O1", 10L, status, claim, claimNo, "p", "o", "v", 1, null, null,
                new StageTimes(T0.minusDays(1), latest, null, null, null, null, null));
    }

    static OrderLineState state(OrderStatus status, LocalDateTime latest, String claim, Long claimNo) {
        return new OrderLineState(1L, "O1", 10L, status, claim, claimNo, "p", "o", "v", 1, null, null, latest, 0);
    }
}
