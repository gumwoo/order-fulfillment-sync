package io.github.gumwoo.fulfillment.worker.message;

import io.github.gumwoo.fulfillment.core.cancel.CancelService;
import io.github.gumwoo.fulfillment.core.order.ApplyStatusChangeService;
import io.github.gumwoo.fulfillment.core.order.ApplyStatusChangeService.Outcome;
import io.github.gumwoo.fulfillment.core.order.ClaimStatus;
import io.github.gumwoo.fulfillment.core.order.OrderStatus;
import io.github.gumwoo.fulfillment.core.order.OrderStatusChange;
import io.github.gumwoo.fulfillment.core.port.ShopbyClient;
import io.github.gumwoo.fulfillment.core.port.TplClient;
import io.github.gumwoo.fulfillment.core.port.queue.MessageHandler;
import io.github.gumwoo.fulfillment.core.port.queue.MessageHandler.Disposition;
import io.github.gumwoo.fulfillment.core.port.queue.QueueMessage;
import io.github.gumwoo.fulfillment.core.shipment.ShipmentRequestService;
import io.github.gumwoo.fulfillment.infra.json.OrderStatusChangeJson;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * ORDER_STATUS_CHANGED 처리.
 * <ol>
 *   <li>멱등·우선순위 규칙으로 반영한다 (ADR-0001, ADR-0003).</li>
 *   <li>3PL 연동(fulfillment-enabled):
 *     <ul>
 *       <li>결제완료(클레임 없음): 출고 요청 보장 — 3PL 등록 → 샵바이 상품준비중 (단계별 멱등)</li>
 *       <li>취소 신청(CANCEL_REQUEST): 3PL 상태로 분기하는 취소 처리 (ADR-0005)</li>
 *     </ul>
 *   </li>
 * </ol>
 * 샵바이 레이트 리밋(429, EXHAUSTED, 대기 초과)이면 메시지를 재발행한다. receive count를 올리지 않으므로 DLQ로 가지 않는다 (ADR-0007).
 * 재발행된 메시지는 반영 단계에서 DUPLICATE가 되고, 연동 단계만 이어서 한다.
 * 파싱할 수 없는 본문은 지우지 않고 DLQ로 보낸다.
 */
@Component
public class OrderStatusChangedHandler {

    static final int MAX_TPL_RETRIES = 20;

    private static final Logger log = LoggerFactory.getLogger(OrderStatusChangedHandler.class);

    private final OrderStatusChangeJson json;
    private final ApplyStatusChangeService applyService;
    private final ShipmentRequestService shipmentService;
    private final CancelService cancelService;
    private final ProcessingStats stats;
    private final boolean fulfillmentEnabled;

    public OrderStatusChangedHandler(OrderStatusChangeJson json, ApplyStatusChangeService applyService,
                                     ShipmentRequestService shipmentService, CancelService cancelService,
                                     ProcessingStats stats,
                                     @Value("${app.worker.fulfillment-enabled:true}") boolean fulfillmentEnabled) {
        this.json = json;
        this.applyService = applyService;
        this.shipmentService = shipmentService;
        this.cancelService = cancelService;
        this.stats = stats;
        this.fulfillmentEnabled = fulfillmentEnabled;
    }

    public Disposition handle(QueueMessage message) {
        OrderStatusChange change;
        try {
            change = json.parse(message.payload());
        } catch (RuntimeException e) {
            log.warn("주문상태변경 본문 파싱 실패, DLQ로 보낸다: {}", e.toString());
            stats.record(message.source(), "INVALID");
            return new MessageHandler.Redeliver("파싱 실패: " + e.getMessage());
        }
        Outcome outcome = applyService.apply(change, message.source());
        stats.record(message.source(), outcome.name());
        if (!fulfillmentEnabled) {
            return MessageHandler.done();
        }
        if (ClaimStatus.CANCEL_REQUEST.equals(change.claimStatusType()) && change.claimNo() != null) {
            return withRetry(message, () -> {
                stats.record("CANCEL", cancelService.onCancelRequested(change.orderProductOptionNo(), change.claimNo()).name());
                return null;
            });
        }
        if (change.orderStatus() == OrderStatus.PAY_DONE && !change.hasClaim()) {
            return withRetry(message, () -> {
                stats.record("SHIPMENT", shipmentService.ensureRequested(change.orderProductOptionNo()).name());
                return null;
            });
        }
        return MessageHandler.done();
    }

    /** 연동 단계의 일시 실패를 재발행으로 바꾼다 */
    private Disposition withRetry(QueueMessage message, Supplier<Void> work) {
        try {
            work.get();
            return MessageHandler.done();
        } catch (ShopbyClient.RateLimitedException e) {
            stats.record("SHOPBY", "RATE_LIMITED");
            QueueMessage next = message.withRateLimitRetry();
            return new MessageHandler.Republish(next, backoff(next.rateLimitRetries()));
        } catch (TplClient.TplException e) {
            stats.record("TPL", "UNAVAILABLE");
            if (message.attempt() >= MAX_TPL_RETRIES) {
                return new MessageHandler.Redeliver("3PL 재시도 한도 초과: " + e.getMessage());
            }
            QueueMessage next = message.withNextAttempt();
            return new MessageHandler.Republish(next, backoff(next.attempt()));
        }
    }

    /** 1, 2, 4, 8, 16, 30, 30 ... 초 */
    static Duration backoff(int retries) {
        long seconds = Math.min(30, 1L << Math.min(5, Math.max(0, retries - 1)));
        return Duration.ofSeconds(seconds);
    }
}
