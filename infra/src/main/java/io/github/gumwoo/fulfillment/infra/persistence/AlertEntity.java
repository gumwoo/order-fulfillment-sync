package io.github.gumwoo.fulfillment.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** 운영자에게 알릴 경보. notifiedAt이 비어 있으면 아직 묶음 알림으로 나가지 않은 것이다 (설계 4.8). */
@Entity
@Table(name = "alert", indexes = @Index(name = "ix_alert_notified", columnList = "notifiedAt"))
public class AlertEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 48)
    private String type;

    @Column(length = 64)
    private String targetKey;

    @Column(length = 1000)
    private String message;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime notifiedAt;

    private Long notificationId;

    protected AlertEntity() {
    }

    public AlertEntity(String type, String targetKey, String message, LocalDateTime createdAt) {
        this.type = type;
        this.targetKey = targetKey;
        this.message = message;
        this.createdAt = createdAt;
    }

    public void markNotified(long notificationId, LocalDateTime at) {
        this.notificationId = notificationId;
        this.notifiedAt = at;
    }

    public Long getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getTargetKey() {
        return targetKey;
    }

    public String getMessage() {
        return message;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
