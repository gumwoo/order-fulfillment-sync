package io.github.gumwoo.fulfillment.mock.shopby;

import java.time.LocalDateTime;

/** mock 샵바이의 주문상품 옵션 하나. 필드 이름은 공개 스펙(external-facts.md)을 따른다. */
public class MockOption {

    long orderProductOptionNo;
    String orderNo;
    long deliveryNo;
    String productName;
    String optionName;
    String optionValue;
    int orderCnt = 1;
    String orderStatusType;
    String claimStatusType;
    Long claimNo;
    LocalDateTime orderYmdt;
    LocalDateTime payYmdt;
    LocalDateTime orderAcceptYmdt;
    LocalDateTime releaseReadyYmdt;
    LocalDateTime releaseYmdt;
    LocalDateTime deliveryCompleteYmdt;
    LocalDateTime buyConfirmYmdt;
    LocalDateTime statusChangeYmdt;
    boolean holdDelivery;
    String deliveryCompanyType;
    String invoiceNo;

    /** 상태가 바뀌면 해당 단계 시각과 statusChangeYmdt를 채운다 */
    void changeStatus(String status, LocalDateTime now) {
        this.orderStatusType = status;
        switch (status) {
            case "PAY_DONE" -> payYmdt = payYmdt == null ? now : payYmdt;
            case "PRODUCT_PREPARE" -> orderAcceptYmdt = now;
            case "DELIVERY_PREPARE" -> releaseReadyYmdt = now;
            case "DELIVERY_ING" -> releaseYmdt = now;
            case "DELIVERY_DONE" -> deliveryCompleteYmdt = now;
            case "BUY_CONFIRM" -> buyConfirmYmdt = now;
            default -> {
                // DEPOSIT_WAIT: 단계 시각 없음
            }
        }
        this.statusChangeYmdt = now;
    }

    void changeClaim(String claimStatus, Long claimNo, LocalDateTime now) {
        this.claimStatusType = claimStatus;
        this.claimNo = claimNo;
        this.statusChangeYmdt = now;
    }

    public long getOrderProductOptionNo() {
        return orderProductOptionNo;
    }

    public String getOrderStatusType() {
        return orderStatusType;
    }

    public String getClaimStatusType() {
        return claimStatusType;
    }

    public boolean isHoldDelivery() {
        return holdDelivery;
    }
}
