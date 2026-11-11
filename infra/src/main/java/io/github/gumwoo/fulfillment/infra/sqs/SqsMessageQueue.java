package io.github.gumwoo.fulfillment.infra.sqs;

import io.github.gumwoo.fulfillment.core.port.queue.MessageQueue;
import io.github.gumwoo.fulfillment.core.port.queue.QueueMessage;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/** 메시지 종류·경로·재시도 횟수는 메시지 속성에, 본문은 payload 그대로 보낸다. */
public class SqsMessageQueue implements MessageQueue {

    static final String ATTR_TYPE = "type";
    static final String ATTR_SOURCE = "source";
    static final String ATTR_ATTEMPT = "attempt";
    static final String ATTR_RATE_LIMIT_RETRIES = "rateLimitRetries";

    /** SQS 메시지 타이머 최대값 (external-facts.md) */
    static final Duration MAX_DELAY = Duration.ofMinutes(15);

    private final SqsClient sqs;
    private final SqsQueues queues;

    public SqsMessageQueue(SqsClient sqs, SqsQueues queues) {
        this.sqs = sqs;
        this.queues = queues;
    }

    @Override
    public void send(QueueMessage message) {
        send(message, Duration.ZERO);
    }

    @Override
    public void send(QueueMessage message, Duration delay) {
        if (delay.compareTo(MAX_DELAY) > 0) {
            throw new IllegalArgumentException("SQS 메시지 지연은 최대 15분이다: " + delay);
        }
        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queues.queueUrl())
                .messageBody(message.payload())
                .delaySeconds((int) delay.toSeconds())
                .messageAttributes(toAttributes(message))
                .build());
    }

    static Map<String, MessageAttributeValue> toAttributes(QueueMessage m) {
        Map<String, MessageAttributeValue> attrs = new HashMap<>();
        attrs.put(ATTR_TYPE, string(m.type()));
        attrs.put(ATTR_SOURCE, string(m.source()));
        attrs.put(ATTR_ATTEMPT, number(m.attempt()));
        attrs.put(ATTR_RATE_LIMIT_RETRIES, number(m.rateLimitRetries()));
        return attrs;
    }

    static QueueMessage fromSqs(Message m) {
        Map<String, MessageAttributeValue> a = m.messageAttributes();
        return new QueueMessage(
                text(a, ATTR_TYPE, null),
                m.body(),
                text(a, ATTR_SOURCE, null),
                intOrZero(text(a, ATTR_ATTEMPT, "0")),
                intOrZero(text(a, ATTR_RATE_LIMIT_RETRIES, "0")));
    }

    private static int intOrZero(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static MessageAttributeValue string(String v) {
        return MessageAttributeValue.builder().dataType("String").stringValue(v == null || v.isEmpty() ? "-" : v).build();
    }

    private static MessageAttributeValue number(int v) {
        return MessageAttributeValue.builder().dataType("Number").stringValue(String.valueOf(v)).build();
    }

    private static String text(Map<String, MessageAttributeValue> a, String key, String fallback) {
        MessageAttributeValue v = a.get(key);
        return v == null || v.stringValue() == null ? fallback : v.stringValue();
    }
}
