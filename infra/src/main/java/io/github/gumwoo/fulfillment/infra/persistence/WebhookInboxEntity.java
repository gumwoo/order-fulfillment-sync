package io.github.gumwoo.fulfillment.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** 수신한 웹훅 원본. 비즈니스 처리 전에 저장한다 (설계 4.1). */
@Entity
@Table(name = "webhook_inbox")
public class WebhookInboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime receivedAt;

    @Column(length = 64)
    private String eventType;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String rawJson;

    /** QUEUED: 원소별 outbox 작성됨, IGNORED: 처리 대상 아닌 이벤트, INVALID: 파싱 실패 */
    @Column(nullable = false, length = 16)
    private String status;

    protected WebhookInboxEntity() {
    }

    public WebhookInboxEntity(LocalDateTime receivedAt, String eventType, String rawJson, String status) {
        this.receivedAt = receivedAt;
        this.eventType = eventType;
        this.rawJson = rawJson;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public String getRawJson() {
        return rawJson;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }
}
