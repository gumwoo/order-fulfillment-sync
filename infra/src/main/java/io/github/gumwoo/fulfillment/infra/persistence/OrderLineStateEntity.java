package io.github.gumwoo.fulfillment.infra.persistence;

import io.github.gumwoo.fulfillment.core.order.OrderLineState;
import io.github.gumwoo.fulfillment.core.order.OrderStatus;
import io.github.gumwoo.fulfillment.core.order.OrderStatusChange;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;

/** 주문 라인(주문상품 옵션)별 현재 상태. version으로 동시 갱신을 막는다 (ADR-0006). */
@Entity
@Table(name = "order_line_state", indexes = {
        @Index(name = "ix_order_line_order_no", columnList = "orderNo"),
        @Index(name = "ix_order_line_delivery_no", columnList = "deliveryNo")})
public class OrderLineStateEntity {

    @Id
    private Long orderProductOptionNo;

    @Column(nullable = false, length = 32)
    private String orderNo;

    private Long deliveryNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status;

    @Column(length = 40)
    private String claimStatus;

    private Long claimNo;

    private String productName;
    private String optionName;
    private String optionValue;
    private int orderCnt;

    @Column(length = 40)
    private String deliveryCompanyType;

    @Column(length = 64)
    private String invoiceNo;

    private LocalDateTime stageYmdt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Version
    private long version;

    protected OrderLineStateEntity() {
    }

    public OrderLineStateEntity(long orderProductOptionNo) {
        this.orderProductOptionNo = orderProductOptionNo;
    }

    public void apply(OrderStatusChange c, LocalDateTime now) {
        this.orderNo = c.orderNo();
        if (c.deliveryNo() != null) {
            this.deliveryNo = c.deliveryNo();
        }
        boolean advanced = status == null || c.orderStatus().isAfter(status);
        this.status = c.orderStatus();
        if (c.hasClaim()) {
            this.claimStatus = c.claimStatusType();
            this.claimNo = c.claimNo();
        } else if (advanced && claimStatus != null && !io.github.gumwoo.fulfillment.core.order.ClaimStatus.isCancelDone(claimStatus)) {
            // 진행 중이던 클레임 없이 주문이 앞 단계로 갔다: 클레임이 철회됐다 (예: 이미출고 처리)
            this.claimStatus = null;
            this.claimNo = null;
        }
        if (c.productName() != null) {
            this.productName = c.productName();
        }
        if (c.optionName() != null) {
            this.optionName = c.optionName();
        }
        if (c.optionValue() != null) {
            this.optionValue = c.optionValue();
        }
        if (c.orderCnt() > 0) {
            this.orderCnt = c.orderCnt();
        }
        if (c.deliveryCompanyType() != null) {
            this.deliveryCompanyType = c.deliveryCompanyType();
        }
        if (c.invoiceNo() != null) {
            this.invoiceNo = c.invoiceNo();
        }
        LocalDateTime latest = c.stageTimes().latest();
        // 클레임만 바뀐 변경이 더 이른 단계 시각을 가져와도 되돌리지 않는다 (같은 단계의 낡은 이벤트 판정이 흔들리지 않게)
        if (latest != null && (stageYmdt == null || latest.isAfter(stageYmdt))) {
            this.stageYmdt = latest;
        }
        this.updatedAt = now;
    }

    public OrderLineState toDomain() {
        return new OrderLineState(orderProductOptionNo, orderNo, deliveryNo, status, claimStatus, claimNo, productName,
                optionName, optionValue, orderCnt, deliveryCompanyType, invoiceNo, stageYmdt, version);
    }

    public Long getOrderProductOptionNo() {
        return orderProductOptionNo;
    }

    public long getVersion() {
        return version;
    }

    public OrderStatus getStatus() {
        return status;
    }
}
