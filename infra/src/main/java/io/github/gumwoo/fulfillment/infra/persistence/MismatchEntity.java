package io.github.gumwoo.fulfillment.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** 대사 배치가 찾은 불일치. 한 번의 대사 실행(runId)마다 새로 기록한다 (설계 4.4). */
@Entity
@Table(name = "mismatch", indexes = @Index(name = "ix_mismatch_run", columnList = "runId"))
public class MismatchEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String runId;

    @Column(nullable = false)
    private Long orderProductOptionNo;

    @Column(nullable = false, length = 48)
    private String reasonCode;

    @Column(length = 64)
    private String shopbyValue;

    @Column(length = 64)
    private String ourValue;

    @Column(nullable = false)
    private LocalDateTime detectedAt;

    protected MismatchEntity() {
    }

    public MismatchEntity(String runId, Long orderProductOptionNo, String reasonCode, String shopbyValue, String ourValue,
                          LocalDateTime detectedAt) {
        this.runId = runId;
        this.orderProductOptionNo = orderProductOptionNo;
        this.reasonCode = reasonCode;
        this.shopbyValue = shopbyValue;
        this.ourValue = ourValue;
        this.detectedAt = detectedAt;
    }
}
