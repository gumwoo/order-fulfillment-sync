package io.github.gumwoo.fulfillment.core.shipment;

import io.github.gumwoo.fulfillment.core.order.OrderLineState;
import io.github.gumwoo.fulfillment.core.order.OrderStatus;
import io.github.gumwoo.fulfillment.core.port.ConflictRecorder;
import io.github.gumwoo.fulfillment.core.port.OrderSyncStore;
import io.github.gumwoo.fulfillment.core.port.ShipmentStore;
import io.github.gumwoo.fulfillment.core.port.ShipmentStore.Shipment;
import io.github.gumwoo.fulfillment.core.port.ShopbyClient;
import io.github.gumwoo.fulfillment.core.port.TplClient;
import io.github.gumwoo.fulfillment.core.port.TplClient.OrderRequest;
import io.github.gumwoo.fulfillment.core.port.TplClient.Registration;
import io.github.gumwoo.fulfillment.core.port.TransactionRunner;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 결제완료 주문옵션의 출고 요청: 3PL 출고요청 등록 → 샵바이 상품준비중 변경.
 * 단계마다 결과를 기록하므로 같은 메시지를 여러 번 처리해도(재발행·중복) 각 단계는 한 번만 일어난다.
 * <ul>
 *   <li>옵션명이 3PL 매핑표에 없으면 미연동(unlinked)으로 기록하고 멈춘다 (파스토 "미연동 내역", external-facts.md)</li>
 *   <li>샵바이 호출이 레이트 리밋에 걸리면 RateLimitedException이 그대로 나간다 → 호출자가 재발행 (ADR-0007)</li>
 * </ul>
 */
public class ShipmentRequestService {

    public static final String UNLINKED_OPTION_NOT_MAPPED = "OPTION_NOT_MAPPED";
    public static final String CONFLICT_PREPARE_REJECTED = "PREPARE_PRODUCT_REJECTED";

    public enum Result {
        /** 대상 아님 (결제완료가 아니거나 클레임 진행 중) */
        SKIPPED,
        /** 3PL이 거절해 미연동으로 기록 */
        UNLINKED,
        /** 3PL 등록과 상품준비중 변경까지 끝남(이번에 했거나 이미 돼 있음) */
        REQUESTED
    }

    private final OrderSyncStore orders;
    private final ShipmentStore shipments;
    private final TplClient tpl;
    private final ShopbyClient shopby;
    private final ConflictRecorder conflicts;
    private final TransactionRunner tx;
    private final Clock clock;

    public ShipmentRequestService(OrderSyncStore orders, ShipmentStore shipments, TplClient tpl, ShopbyClient shopby,
                                  ConflictRecorder conflicts, TransactionRunner tx, Clock clock) {
        this.orders = orders;
        this.shipments = shipments;
        this.tpl = tpl;
        this.shopby = shopby;
        this.conflicts = conflicts;
        this.tx = tx;
        this.clock = clock;
    }

    public Result ensureRequested(long orderProductOptionNo) {
        OrderLineState line = tx.inTransaction(() -> orders.findLine(orderProductOptionNo).orElse(null));
        Shipment shipment = tx.inTransaction(() -> shipments.find(orderProductOptionNo).orElse(null));
        if (shipment != null && shipment.registered() && shipment.prepared()) {
            return Result.REQUESTED;
        }
        if (line == null || line.hasClaim()
                || (shipment == null && line.orderStatus() != OrderStatus.PAY_DONE)) {
            return Result.SKIPPED;
        }
        if (shipment == null || !shipment.registered()) {
            Registration r = tpl.register(new OrderRequest(line.orderProductOptionNo(), line.orderNo(), line.deliveryNo(),
                    line.productName(), line.optionName(), line.optionValue(), Math.max(1, line.orderCnt())));
            LocalDateTime now = LocalDateTime.now(clock);
            if (!r.accepted()) {
                tx.inTransaction(() -> {
                    shipments.recordUnlinkedIfAbsent(orderProductOptionNo, r.reasonCode(), r.detail(), now);
                    return null;
                });
                return Result.UNLINKED;
            }
            tx.inTransaction(() -> {
                shipments.markRegistered(orderProductOptionNo, line.deliveryNo(), r.status(), now);
                shipments.resolveUnlinked(orderProductOptionNo, "SYSTEM", now);
                return null;
            });
        }
        // 3PL 등록 사이에 취소 신청이 들어왔을 수 있으므로 상품준비중 변경 직전에 다시 읽는다
        OrderLineState latest = tx.inTransaction(() -> orders.findLine(orderProductOptionNo).orElse(line));
        if (latest.orderStatus() == OrderStatus.PAY_DONE && !latest.hasClaim()) {
            try {
                shopby.prepareProduct(List.of(orderProductOptionNo));
            } catch (ShopbyClient.ApiException e) {
                if (e.status() != 400) {
                    // 인증·권한·서버 오류 등: 처리했다고 표시하면 조용히 유실된다. 예외로 재전송 → DLQ에서 보이게 한다
                    throw e;
                }
                // 400: 샵바이가 상태 변경을 거절했다(이미 결제완료가 아님 등, 오류 코드는 미확인).
                // 같은 요청을 반복해도 같으므로 멈추고 사람이 보게 conflict로 남긴다
                LocalDateTime at = LocalDateTime.now(clock);
                tx.inTransaction(() -> {
                    conflicts.record(String.valueOf(orderProductOptionNo), CONFLICT_PREPARE_REJECTED, e.getMessage(), at);
                    return null;
                });
            }
        }
        LocalDateTime now = LocalDateTime.now(clock);
        tx.inTransaction(() -> {
            shipments.markPrepared(orderProductOptionNo, now);
            return null;
        });
        return Result.REQUESTED;
    }
}
