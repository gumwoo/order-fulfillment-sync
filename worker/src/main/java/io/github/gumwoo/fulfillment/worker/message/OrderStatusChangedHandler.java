package io.github.gumwoo.fulfillment.worker.message;

import io.github.gumwoo.fulfillment.core.order.ApplyStatusChangeService;
import io.github.gumwoo.fulfillment.core.order.ApplyStatusChangeService.Outcome;
import io.github.gumwoo.fulfillment.core.order.OrderStatusChange;
import io.github.gumwoo.fulfillment.core.port.queue.MessageHandler;
import io.github.gumwoo.fulfillment.core.port.queue.MessageHandler.Disposition;
import io.github.gumwoo.fulfillment.core.port.queue.QueueMessage;
import io.github.gumwoo.fulfillment.infra.json.OrderStatusChangeJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * ORDER_STATUS_CHANGED: 멱등·우선순위 규칙으로 반영한다 (ADR-0001, ADR-0003).
 * 파싱할 수 없는 본문은 지우지 않고 DLQ로 보낸다. 반영 중 예외(낙관적 락 충돌 등)는 소비자가 재전송으로 처리한다.
 */
@Component
public class OrderStatusChangedHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderStatusChangedHandler.class);

    private final OrderStatusChangeJson json;
    private final ApplyStatusChangeService applyService;
    private final ProcessingStats stats;

    public OrderStatusChangedHandler(OrderStatusChangeJson json, ApplyStatusChangeService applyService,
                                     ProcessingStats stats) {
        this.json = json;
        this.applyService = applyService;
        this.stats = stats;
    }

    public Disposition handle(QueueMessage message) {
        OrderStatusChange change;
        try {
            change = json.parse(message.payload());
        } catch (RuntimeException e) {
            // 지우지 않는다: 형식이 예상과 다르면 재조회로도 복구되지 않으므로, 재전송 끝에 DLQ에 남겨 사람이 보게 한다
            log.warn("주문상태변경 본문 파싱 실패, DLQ로 보낸다: {}", e.toString());
            stats.record(message.source(), "INVALID");
            return new MessageHandler.Redeliver("파싱 실패: " + e.getMessage());
        }
        Outcome outcome = applyService.apply(change, message.source());
        stats.record(message.source(), outcome.name());
        return MessageHandler.done();
    }
}
