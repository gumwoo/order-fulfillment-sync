package io.github.gumwoo.fulfillment.core.order;

import java.time.LocalDateTime;

/**
 * 우리 쪽이 알고 있는 주문 라인(주문상품 옵션)의 현재 상태.
 *
 * @param version 낙관적 락 버전 (ADR-0006: 같은 라인을 두 소비자가 동시에 처리하는 경우)
 */
public record OrderLineState(
        long orderProductOptionNo,
        String orderNo,
        Long deliveryNo,
        OrderStatus orderStatus,
        String claimStatusType,
        Long claimNo,
        String productName,
        String optionName,
        String optionValue,
        int orderCnt,
        String deliveryCompanyType,
        String invoiceNo,
        LocalDateTime latestStageTime,
        long version) {

    public static OrderLineState from(OrderStatusChange c, long version) {
        return new OrderLineState(c.orderProductOptionNo(), c.orderNo(), c.deliveryNo(), c.orderStatus(),
                c.claimStatusType(), c.claimNo(), c.productName(), c.optionName(), c.optionValue(), c.orderCnt(),
                c.deliveryCompanyType(), c.invoiceNo(), c.stageTimes().latest(), version);
    }

    public boolean hasClaim() {
        return claimStatusType != null && !claimStatusType.isBlank();
    }
}
