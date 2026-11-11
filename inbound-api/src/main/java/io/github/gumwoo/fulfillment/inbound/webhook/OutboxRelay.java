package io.github.gumwoo.fulfillment.inbound.webhook;

import io.github.gumwoo.fulfillment.core.port.queue.MessageQueue;
import io.github.gumwoo.fulfillment.core.port.queue.QueueMessage;
import io.github.gumwoo.fulfillment.infra.persistence.OutboxEntity;
import io.github.gumwoo.fulfillment.infra.persistence.OutboxRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PENDING outbox를 SQS로 발행하고 SENT로 바꾼다.
 * <ul>
 *   <li>행 선점: {@code FOR UPDATE SKIP LOCKED}로 잠근 행만 처리하므로 inbound 인스턴스가 여러 대여도
 *       같은 행을 동시에 발행하지 않는다.</li>
 *   <li>발행 후 커밋 전에 죽으면 행이 PENDING으로 남아 한 번 더 나갈 수 있다. 소비자가 멱등이므로 허용한다 (ADR-0001).</li>
 *   <li>발행이 실패한 행은 시도 횟수만 올리고 다음 행으로 넘어간다. {@value #MAX_ATTEMPTS}회 실패하면 FAILED로 둔다.</li>
 * </ul>
 */
@Component
public class OutboxRelay {

    static final int BATCH = 100;
    static final int MAX_ATTEMPTS = 10;

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outboxRepository;
    private final MessageQueue messageQueue;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OutboxRelay(OutboxRepository outboxRepository, MessageQueue messageQueue, TransactionTemplate tx, Clock clock) {
        this.outboxRepository = outboxRepository;
        this.messageQueue = messageQueue;
        this.tx = tx;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.outbox.relay-interval-ms:200}")
    public void relay() {
        Integer handled;
        do {
            handled = tx.execute(s -> relayBatch());
        } while (handled != null && handled == BATCH);
    }

    /** 한 트랜잭션: 행 잠금 → 발행 → 상태 변경 → 커밋(잠금 해제) */
    int relayBatch() {
        List<OutboxEntity> rows = outboxRepository.lockPending(BATCH);
        LocalDateTime now = LocalDateTime.now(clock);
        for (OutboxEntity row : rows) {
            try {
                messageQueue.send(QueueMessage.of(row.getMessageType(), row.getPayload(), row.getSource()));
                row.markSent(now);
            } catch (RuntimeException e) {
                row.recordFailure(e.toString(), MAX_ATTEMPTS);
                log.warn("outbox 발행 실패 id={} attempts={}: {}", row.getId(), row.getAttempts(), e.toString());
            }
        }
        return rows.size();
    }
}
