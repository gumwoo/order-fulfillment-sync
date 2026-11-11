package io.github.gumwoo.fulfillment.core.port.queue;

import java.time.Duration;

/** 주문 이벤트 큐 발행 포트. 구현은 infra(SQS). */
public interface MessageQueue {

    void send(QueueMessage message);

    /** 지연 발행. 최대 15분 (SQS 메시지 타이머 한도, external-facts.md) */
    void send(QueueMessage message, Duration delay);
}
