package io.github.gumwoo.fulfillment.worker.message;

import io.github.gumwoo.fulfillment.core.port.queue.MessageHandler;
import io.github.gumwoo.fulfillment.core.port.queue.MessageType;
import io.github.gumwoo.fulfillment.core.port.queue.QueueMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 메시지 종류별로 처리기를 고른다. 모르는 종류는 지운다(재전송해도 처리할 수 없다). */
@Component
public class MessageRouter implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(MessageRouter.class);

    private final OrderStatusChangedHandler orderStatusChanged;

    public MessageRouter(OrderStatusChangedHandler orderStatusChanged) {
        this.orderStatusChanged = orderStatusChanged;
    }

    @Override
    public Disposition handle(QueueMessage message) {
        if (MessageType.ORDER_STATUS_CHANGED.equals(message.type())) {
            return orderStatusChanged.handle(message);
        }
        log.warn("알 수 없는 메시지 종류 {}, 삭제", message.type());
        return MessageHandler.done();
    }
}
