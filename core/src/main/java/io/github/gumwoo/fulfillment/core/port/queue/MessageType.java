package io.github.gumwoo.fulfillment.core.port.queue;

/** 큐 메시지 종류 */
public final class MessageType {

    /** 주문상품 옵션 하나의 상태 변경. 본문은 샵바이 CHANGE_ORDER_STATUS 원소 형태의 JSON */
    public static final String ORDER_STATUS_CHANGED = "ORDER_STATUS_CHANGED";

    /** 3PL 출고 이벤트(출고 상태 변경, 송장 발급) */
    public static final String TPL_EVENT = "TPL_EVENT";

    private MessageType() {
    }
}
