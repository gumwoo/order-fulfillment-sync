package io.github.gumwoo.fulfillment.infra.persistence;

import io.github.gumwoo.fulfillment.core.cancel.CancelState;
import io.github.gumwoo.fulfillment.core.port.CancelStore.CancelCase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.LocalDateTime;

/** 취소 건의 진행 단계 (ADR-0005). 주문옵션 + 클레임번호마다 하나. */
@Entity
@Table(name = "cancel_case",
        uniqueConstraints = @UniqueConstraint(name = "uq_cancel_case", columnNames = {"orderProductOptionNo", "claimNo"}),
        indexes = @Index(name = "ix_cancel_case_state", columnList = "state"))
public class CancelCaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderProductOptionNo;

    @Column(nullable = false)
    private Long claimNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private CancelState state;

    @Column(length = 16)
    private String tplStatusAtRequest;

    private int fee;

    @Column(length = 300)
    private String detail;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Version
    private long version;

    protected CancelCaseEntity() {
    }

    public CancelCaseEntity(long optionNo, long claimNo, LocalDateTime now) {
        this.orderProductOptionNo = optionNo;
        this.claimNo = claimNo;
        this.state = CancelState.RECEIVED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(CancelState state, String tplStatus, Integer fee, String detail, LocalDateTime now) {
        this.state = state;
        if (tplStatus != null) {
            this.tplStatusAtRequest = tplStatus;
        }
        if (fee != null) {
            this.fee = fee;
        }
        if (detail != null) {
            this.detail = detail;
        }
        this.updatedAt = now;
    }

    public CancelCase toDomain() {
        return new CancelCase(orderProductOptionNo, claimNo, state, tplStatusAtRequest, fee, detail);
    }
}
