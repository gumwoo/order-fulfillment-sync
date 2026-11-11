package io.github.gumwoo.fulfillment.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** 큐로 발행할 메시지. 원본 저장과 같은 트랜잭션에서 쓰고 릴레이가 발행한다 (설계 4.1). */
@Entity
@Table(name = "outbox", indexes = @Index(name = "ix_outbox_status_id", columnList = "status, id"))
public class OutboxEntity {

    public static final String PENDING = "PENDING";
    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long inboxId;

    @Column(nullable = false, length = 32)
    private String messageType;

    @Column(nullable = false, length = 32)
    private String source;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String payload;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime sentAt;

    private int attempts;

    @Column(length = 500)
    private String lastError;

    protected OutboxEntity() {
    }

    /** 발행 실패: 시도 횟수를 올리고, 한도에 닿으면 FAILED로 둔다 (다른 행을 막지 않게) */
    public void recordFailure(String error, int maxAttempts) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
        if (attempts >= maxAttempts) {
            this.status = FAILED;
        }
    }

    public int getAttempts() {
        return attempts;
    }

    public OutboxEntity(Long inboxId, String messageType, String source, String payload, LocalDateTime createdAt) {
        this.inboxId = inboxId;
        this.messageType = messageType;
        this.source = source;
        this.payload = payload;
        this.status = PENDING;
        this.createdAt = createdAt;
    }

    public void markSent(LocalDateTime now) {
        this.status = SENT;
        this.sentAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getInboxId() {
        return inboxId;
    }

    public String getMessageType() {
        return messageType;
    }

    public String getSource() {
        return source;
    }

    public String getPayload() {
        return payload;
    }

    public String getStatus() {
        return status;
    }
}
