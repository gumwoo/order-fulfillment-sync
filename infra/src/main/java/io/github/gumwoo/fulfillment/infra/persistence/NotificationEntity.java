package io.github.gumwoo.fulfillment.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** 묶음 알림 (Slack 발송 대기열). 실제 발송은 범위 밖이다 (설계 2, 4.8). */
@Entity
@Table(name = "notification")
public class NotificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String channel;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    private int alertCount;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime sentAt;

    protected NotificationEntity() {
    }

    public NotificationEntity(String channel, String text, int alertCount, LocalDateTime createdAt) {
        this.channel = channel;
        this.text = text;
        this.alertCount = alertCount;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }
}
