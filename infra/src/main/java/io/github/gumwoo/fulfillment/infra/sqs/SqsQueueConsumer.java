package io.github.gumwoo.fulfillment.infra.sqs;

import io.github.gumwoo.fulfillment.core.port.queue.MessageHandler;
import io.github.gumwoo.fulfillment.core.port.queue.MessageHandler.Disposition;
import io.github.gumwoo.fulfillment.core.port.queue.MessageHandler.Done;
import io.github.gumwoo.fulfillment.core.port.queue.MessageHandler.Redeliver;
import io.github.gumwoo.fulfillment.core.port.queue.MessageHandler.Republish;
import io.github.gumwoo.fulfillment.core.port.queue.QueueMessage;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityBatchRequest;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * 주문 이벤트 큐 소비 루프. 처리 결과(Disposition)에 따라 삭제, 재발행 후 삭제, 그대로 둠(SQS 재전송)을 한다.
 * 재발행은 receive count를 올리지 않는 재시도다 (ADR-0007).
 */
public class SqsQueueConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SqsQueueConsumer.class);

    private final SqsClient sqs;
    private final SqsQueues queues;
    private final SqsProperties props;
    private final SqsMessageQueue publisher;
    private final MessageHandler handler;
    private volatile boolean running;
    private ExecutorService executor;

    public SqsQueueConsumer(SqsClient sqs, SqsQueues queues, SqsProperties props, SqsMessageQueue publisher,
                            MessageHandler handler) {
        this.sqs = sqs;
        this.queues = queues;
        this.props = props;
        this.publisher = publisher;
        this.handler = handler;
    }

    @Override
    public void start() {
        running = true;
        executor = Executors.newFixedThreadPool(props.consumerThreads(), r -> {
            Thread t = new Thread(r, "sqs-consumer");
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < props.consumerThreads(); i++) {
            executor.submit(this::loop);
        }
    }

    private void loop() {
        while (running) {
            try {
                List<Message> messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queues.queueUrl())
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(props.waitTimeSeconds())
                        .messageAttributeNames("All")
                        .build()).messages();
                long receivedAt = System.currentTimeMillis();
                for (int i = 0; i < messages.size(); i++) {
                    Message m = messages.get(i);
                    if (!running) {
                        return;
                    }
                    // 묶음을 차례로 처리하는 동안 뒤쪽 메시지의 visibility timeout이 끝나면 다른 소비자가 다시 받아
                    // receive count가 오른다(F-07에서 maxReceiveCount=1로 재현). 절반이 지나면 남은 메시지를 연장한다.
                    long halfMs = props.visibilityTimeoutSeconds() * 500L;
                    if (System.currentTimeMillis() - receivedAt > halfMs) {
                        extendVisibility(messages.subList(i, messages.size()));
                        receivedAt = System.currentTimeMillis();
                    }
                    try {
                        process(m);
                    } catch (RuntimeException e) {
                        // 한 메시지의 실패(삭제·재발행 실패 등)가 같은 배치의 나머지를 막지 않게 한다.
                        // 이 메시지는 지워지지 않았으므로 visibility timeout 뒤 다시 온다.
                        log.warn("메시지 후처리 실패 id={}: {}", m.messageId(), e.toString());
                    }
                }
            } catch (RuntimeException e) {
                if (running) {
                    log.warn("SQS 수신 실패, 잠시 후 다시 시도: {}", e.toString());
                    sleepQuietly(1000);
                }
            }
        }
    }

    void process(Message m) {
        QueueMessage message = SqsMessageQueue.fromSqs(m);
        Disposition disposition;
        try {
            disposition = handler.handle(message);
        } catch (RuntimeException e) {
            log.warn("메시지 처리 실패 type={} source={}: {}", message.type(), message.source(), e.toString());
            disposition = new Redeliver(e.toString());
        }
        // Java 17: switch 패턴 매칭 대신 instanceof
        if (disposition instanceof Done) {
            delete(m);
        } else if (disposition instanceof Republish r) {
            // 재발행이 실패하면 예외가 나가고 원본은 지워지지 않는다(유실 없음, 대신 receive count가 오른다)
            Duration delay = r.delay().compareTo(SqsMessageQueue.MAX_DELAY) > 0 ? SqsMessageQueue.MAX_DELAY : r.delay();
            publisher.send(r.next(), delay);
            delete(m);
        } else if (disposition instanceof Redeliver r) {
            log.debug("재전송 대기: {}", r.reason());
        }
    }

    private void extendVisibility(List<Message> remaining) {
        try {
            List<ChangeMessageVisibilityBatchRequestEntry> entries = new java.util.ArrayList<>();
            for (int i = 0; i < remaining.size(); i++) {
                entries.add(ChangeMessageVisibilityBatchRequestEntry.builder()
                        .id(String.valueOf(i))
                        .receiptHandle(remaining.get(i).receiptHandle())
                        .visibilityTimeout(props.visibilityTimeoutSeconds())
                        .build());
            }
            sqs.changeMessageVisibilityBatch(ChangeMessageVisibilityBatchRequest.builder()
                    .queueUrl(queues.queueUrl()).entries(entries).build());
        } catch (RuntimeException e) {
            log.warn("visibility 연장 실패: {}", e.toString());
        }
    }

    private void delete(Message m) {
        sqs.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queues.queueUrl()).receiptHandle(m.receiptHandle()).build());
    }

    @Override
    public void stop() {
        running = false;
        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(props.waitTimeSeconds() + 5L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
