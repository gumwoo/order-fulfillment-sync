package io.github.gumwoo.fulfillment.core.order;

/**
 * 샵바이 주문옵션 상태 (external-facts.md). 선언 순서가 진행 순서(우선순위)다 (ADR-0003).
 */
public enum OrderStatus {
    DEPOSIT_WAIT,
    PAY_DONE,
    PRODUCT_PREPARE,
    DELIVERY_PREPARE,
    DELIVERY_ING,
    DELIVERY_DONE,
    BUY_CONFIRM;

    public int rank() {
        return ordinal();
    }

    public boolean isAfter(OrderStatus other) {
        return rank() > other.rank();
    }

    /** 출고 전 상태 (배송보류·상품준비중 변경 대상 판단용) */
    public boolean isBeforeShipment() {
        return rank() <= DELIVERY_PREPARE.rank();
    }
}
