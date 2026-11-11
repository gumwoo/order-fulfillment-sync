package io.github.gumwoo.fulfillment.infra.persistence;

import io.github.gumwoo.fulfillment.core.port.ShipmentStore.Shipment;
import io.github.gumwoo.fulfillment.core.port.TplClient.Status;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;

/** 주문옵션별 3PL 출고 상태와 진행 단계 표시 (설계 5. shipment) */
@Entity
@Table(name = "shipment", indexes = @Index(name = "ix_shipment_delivery_no", columnList = "deliveryNo"))
public class ShipmentEntity {

    @Id
    private Long orderProductOptionNo;

    private Long deliveryNo;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Status tplStatus;

    private LocalDateTime registeredAt;
    private LocalDateTime preparedAt;

    @Column(length = 64)
    private String invoiceNo;

    @Column(length = 40)
    private String deliveryCompanyType;

    /** 송장을 샵바이에 반영한 시각 (F-11) */
    private LocalDateTime invoiceSyncedAt;

    private int invoiceSyncFailures;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Version
    private long version;

    protected ShipmentEntity() {
    }

    public ShipmentEntity(long orderProductOptionNo, LocalDateTime now) {
        this.orderProductOptionNo = orderProductOptionNo;
        this.updatedAt = now;
    }

    public Shipment toDomain() {
        return new Shipment(orderProductOptionNo, deliveryNo, tplStatus, registeredAt != null, preparedAt != null,
                invoiceNo, deliveryCompanyType);
    }

    public void register(Long deliveryNo, Status status, LocalDateTime now) {
        if (registeredAt == null) {
            registeredAt = now;
        }
        if (deliveryNo != null) {
            this.deliveryNo = deliveryNo;
        }
        if (tplStatus == null) {
            tplStatus = status;
        }
        updatedAt = now;
    }

    public void prepared(LocalDateTime now) {
        if (preparedAt == null) {
            preparedAt = now;
        }
        updatedAt = now;
    }

    public void tplStatus(Status status, LocalDateTime now) {
        this.tplStatus = status;
        updatedAt = now;
    }

    public void invoice(String invoiceNo, String deliveryCompanyType, LocalDateTime now) {
        this.invoiceNo = invoiceNo;
        this.deliveryCompanyType = deliveryCompanyType;
        updatedAt = now;
    }

    public void invoiceSynced(LocalDateTime now) {
        this.invoiceSyncedAt = now;
        updatedAt = now;
    }

    public void invoiceSyncFailed(LocalDateTime now) {
        this.invoiceSyncFailures++;
        updatedAt = now;
    }

    public Long getOrderProductOptionNo() {
        return orderProductOptionNo;
    }

    public Long getDeliveryNo() {
        return deliveryNo;
    }

    public Status getTplStatus() {
        return tplStatus;
    }

    public String getInvoiceNo() {
        return invoiceNo;
    }

    public String getDeliveryCompanyType() {
        return deliveryCompanyType;
    }

    public LocalDateTime getInvoiceSyncedAt() {
        return invoiceSyncedAt;
    }

    public int getInvoiceSyncFailures() {
        return invoiceSyncFailures;
    }
}
