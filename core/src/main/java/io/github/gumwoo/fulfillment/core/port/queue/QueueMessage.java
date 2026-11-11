package io.github.gumwoo.fulfillment.core.port.queue;

/**
 * 주문 이벤트 큐에 오가는 메시지.
 *
 * @param type             메시지 종류 ({@link MessageType})
 * @param payload          본문(JSON 문자열)
 * @param source           들어온 경로 (WEBHOOK, FAILED_WEBHOOK, POLLING, ...)
 * @param attempt          지연 재처리 등 업무상 재시도 횟수
 * @param rateLimitRetries 429·레이트 리미터 대기로 재발행된 횟수 (ADR-0007, 관측용)
 */
public record QueueMessage(String type, String payload, String source, int attempt, int rateLimitRetries) {

    public static QueueMessage of(String type, String payload, String source) {
        return new QueueMessage(type, payload, source, 0, 0);
    }

    public QueueMessage withRateLimitRetry() {
        return new QueueMessage(type, payload, source, attempt, rateLimitRetries + 1);
    }

    public QueueMessage withNextAttempt() {
        return new QueueMessage(type, payload, source, attempt + 1, rateLimitRetries);
    }
}
