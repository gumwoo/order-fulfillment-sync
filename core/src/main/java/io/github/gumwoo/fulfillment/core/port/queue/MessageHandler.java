package io.github.gumwoo.fulfillment.core.port.queue;

/** 큐 소비자가 메시지 하나를 처리하는 함수. */
@FunctionalInterface
public interface MessageHandler {

    Disposition handle(QueueMessage message);

    /** 처리 결과에 따라 큐 어댑터가 할 일 */
    sealed interface Disposition permits Done, Republish, Redeliver {
    }

    /** 처리 완료. 메시지를 지운다. */
    record Done() implements Disposition {
    }

    /**
     * 원래 메시지를 지우고 새 메시지를 지연 발행한다 (receive count를 올리지 않는 재시도, ADR-0007).
     */
    record Republish(QueueMessage next, java.time.Duration delay) implements Disposition {
    }

    /** 메시지를 지우지 않는다. SQS 재전송과 maxReceiveCount(DLQ)를 따른다. */
    record Redeliver(String reason) implements Disposition {
    }

    static Disposition done() {
        return new Done();
    }
}
