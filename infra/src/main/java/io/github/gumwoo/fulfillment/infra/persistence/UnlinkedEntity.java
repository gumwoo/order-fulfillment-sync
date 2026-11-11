package io.github.gumwoo.fulfillment.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** 3PL 미연동 건 (옵션명 매핑 실패 등). CS 어드민 목록에 사유 코드와 함께 보인다 (설계 4.9). */
@Entity
@Table(name = "unlinked", indexes = @Index(name = "ix_unlinked_option", columnList = "orderProductOptionNo"),
        uniqueConstraints = @jakarta.persistence.UniqueConstraint(name = "uq_unlinked_open", columnNames = "openKey"))
public class UnlinkedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderProductOptionNo;

    @Column(nullable = false, length = 48)
    private String reasonCode;

    @Column(length = 500)
    private String detail;

    @Column(nullable = false)
    private LocalDateTime detectedAt;

    private LocalDateTime resolvedAt;

    @Column(length = 64)
    private String resolver;

    /** 미해결이면 주문옵션번호, 해결되면 NULL. UNIQUE라 옵션당 미해결 건은 하나다 (MySQL은 NULL 중복 허용) */
    private Long openKey;

    protected UnlinkedEntity() {
    }

    public UnlinkedEntity(Long orderProductOptionNo, String reasonCode, String detail, LocalDateTime detectedAt) {
        this.orderProductOptionNo = orderProductOptionNo;
        this.reasonCode = reasonCode;
        this.detail = detail;
        this.detectedAt = detectedAt;
        this.openKey = orderProductOptionNo;
    }

    public void resolve(String resolver, LocalDateTime at) {
        this.resolver = resolver;
        this.resolvedAt = at;
        this.openKey = null;
    }

    public Long getId() {
        return id;
    }

    public Long getOrderProductOptionNo() {
        return orderProductOptionNo;
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

    public String getResolver() {
        return resolver;
    }
}
