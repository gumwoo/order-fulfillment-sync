package io.github.gumwoo.fulfillment.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** 대사 배치 실행 기록. 불일치가 0인 실행도 남겨야 추이(불일치 → 0 수렴)를 볼 수 있다. */
@Entity
@Table(name = "reconciliation_run", indexes = @Index(name = "ix_reconciliation_run_at", columnList = "ranAt"))
public class ReconciliationRunEntity {

    @Id
    @Column(length = 40)
    private String runId;

    @Column(nullable = false)
    private LocalDateTime ranAt;

    private int compared;

    private int mismatches;

    protected ReconciliationRunEntity() {
    }

    public ReconciliationRunEntity(String runId, LocalDateTime ranAt, int compared, int mismatches) {
        this.runId = runId;
        this.ranAt = ranAt;
        this.compared = compared;
        this.mismatches = mismatches;
    }

    public String getRunId() {
        return runId;
    }

    public LocalDateTime getRanAt() {
        return ranAt;
    }

    public int getCompared() {
        return compared;
    }

    public int getMismatches() {
        return mismatches;
    }
}
