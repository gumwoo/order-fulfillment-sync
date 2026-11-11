package io.github.gumwoo.fulfillment.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** 복구 경로별 마지막 성공 조회 시각 (설계 4.4). */
@Entity
@Table(name = "sync_watermark")
public class SyncWatermarkEntity {

    public static final String POLLING = "POLLING";
    public static final String FAILED_WEBHOOK = "FAILED_WEBHOOK";

    @Id
    @Column(length = 32)
    private String source;

    @Column(nullable = false)
    private LocalDateTime lastSuccessAt;

    protected SyncWatermarkEntity() {
    }

    public SyncWatermarkEntity(String source, LocalDateTime lastSuccessAt) {
        this.source = source;
        this.lastSuccessAt = lastSuccessAt;
    }

    public String getSource() {
        return source;
    }

    public LocalDateTime getLastSuccessAt() {
        return lastSuccessAt;
    }

    public void advanceTo(LocalDateTime at) {
        this.lastSuccessAt = at;
    }
}
