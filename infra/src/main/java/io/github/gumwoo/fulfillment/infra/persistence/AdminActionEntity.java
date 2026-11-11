package io.github.gumwoo.fulfillment.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * CS 어드민 처리 이력 (설계 4.9: 누가 언제 무엇을 했는지). requestId(Idempotency-Key)가 UNIQUE라
 * 같은 요청을 여러 번 보내도 한 번만 처리된다.
 */
@Entity
@Table(name = "admin_action", indexes = @Index(name = "ix_admin_action_target", columnList = "targetKey"))
public class AdminActionEntity {

    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String requestId;

    @Column(nullable = false, length = 64)
    private String actor;

    @Column(nullable = false, length = 32)
    private String action;

    @Column(nullable = false, length = 64)
    private String targetKey;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = 32)
    private String result;

    @Column(length = 500)
    private String detail;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime finishedAt;

    protected AdminActionEntity() {
    }

    public void finish(String status, String result, String detail, LocalDateTime at) {
        this.status = status;
        this.result = result;
        this.detail = detail == null ? null : detail.substring(0, Math.min(detail.length(), 500));
        this.finishedAt = at;
    }

    public Long getId() {
        return id;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getActor() {
        return actor;
    }

    public String getAction() {
        return action;
    }

    public String getTargetKey() {
        return targetKey;
    }

    public String getStatus() {
        return status;
    }

    public String getResult() {
        return result;
    }

    public String getDetail() {
        return detail;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }
}
