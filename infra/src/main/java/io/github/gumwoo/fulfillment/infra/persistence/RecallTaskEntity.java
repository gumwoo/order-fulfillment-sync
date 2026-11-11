package io.github.gumwoo.fulfillment.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/** CS 회수 안내 목록: 취소 신청이 왔지만 이미 피킹·출고돼 고객에게 회수를 안내해야 하는 건 (ADR-0005, F-10) */
@Entity
@Table(name = "recall_task",
        uniqueConstraints = @UniqueConstraint(name = "uq_recall_task", columnNames = {"orderProductOptionNo", "claimNo"}))
public class RecallTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderProductOptionNo;

    @Column(nullable = false)
    private Long claimNo;

    @Column(length = 64)
    private String invoiceNo;

    @Column(nullable = false, length = 48)
    private String reason;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime resolvedAt;

    protected RecallTaskEntity() {
    }

    public RecallTaskEntity(Long orderProductOptionNo, Long claimNo, String invoiceNo, String reason, LocalDateTime createdAt) {
        this.orderProductOptionNo = orderProductOptionNo;
        this.claimNo = claimNo;
        this.invoiceNo = invoiceNo;
        this.reason = reason;
        this.createdAt = createdAt;
    }
}
