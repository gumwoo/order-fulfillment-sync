package io.github.gumwoo.fulfillment.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** 실제로 반영된 상태 변경 이력. 한 행 = 반영 1회 (F-03의 "반영 횟수"). */
@Entity
@Table(name = "status_history", indexes = @Index(name = "ix_status_history_option", columnList = "orderProductOptionNo"))
public class StatusHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderProductOptionNo;

    @Column(length = 32)
    private String fromStatus;

    @Column(nullable = false, length = 32)
    private String toStatus;

    @Column(length = 40)
    private String claimStatus;

    @Column(nullable = false, length = 32)
    private String source;

    @Column(nullable = false, length = 64)
    private String idemKey;

    @Column(nullable = false)
    private LocalDateTime appliedAt;

    protected StatusHistoryEntity() {
    }

    public StatusHistoryEntity(Long orderProductOptionNo, String fromStatus, String toStatus, String claimStatus,
                               String source, String idemKey, LocalDateTime appliedAt) {
        this.orderProductOptionNo = orderProductOptionNo;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.claimStatus = claimStatus;
        this.source = source;
        this.idemKey = idemKey;
        this.appliedAt = appliedAt;
    }
}
