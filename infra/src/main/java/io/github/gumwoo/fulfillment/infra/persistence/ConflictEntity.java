package io.github.gumwoo.fulfillment.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** 자동으로 맞출 수 없어 사람이 봐야 하는 건 (역전이, 지연 재처리 초과, 취소·출고 충돌 등). */
@Entity
@Table(name = "conflict", indexes = @Index(name = "ix_conflict_target", columnList = "targetKey"))
public class ConflictEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String targetKey;

    @Column(nullable = false, length = 48)
    private String reasonCode;

    @Column(length = 1000)
    private String detail;

    @Column(nullable = false)
    private LocalDateTime detectedAt;

    private LocalDateTime resolvedAt;

    @Column(length = 64)
    private String resolver;

    protected ConflictEntity() {
    }

    public ConflictEntity(String targetKey, String reasonCode, String detail, LocalDateTime detectedAt) {
        this.targetKey = targetKey;
        this.reasonCode = reasonCode;
        this.detail = detail;
        this.detectedAt = detectedAt;
    }

    public Long getId() {
        return id;
    }

    public String getTargetKey() {
        return targetKey;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getDetail() {
        return detail;
    }

    public LocalDateTime getDetectedAt() {
        return detectedAt;
    }

    public LocalDateTime getResolvedAt() {
        return resolvedAt;
    }
}
