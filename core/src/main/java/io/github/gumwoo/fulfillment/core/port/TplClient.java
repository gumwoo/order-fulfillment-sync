package io.github.gumwoo.fulfillment.core.port;

import java.util.List;
import java.util.Optional;

/**
 * 3PL(파스토 규칙 mock) 호출 포트. 구현은 infra.tpl.
 * 파스토의 실제 API 형태는 공개 자료로 확인하지 못했다. 규칙(출고 단계, 취소 가능 단계, 수수료, 옵션명 매핑)만
 * external-facts.md를 따르고, 요청·응답 형태는 이 프로젝트의 mock 정의다 (설계 4.7).
 */
public interface TplClient {

    /** 출고요청 등록. 주문옵션번호 기준 멱등(이미 있으면 현재 상태를 돌려준다). 옵션명이 매핑표에 없으면 거절. */
    Registration register(OrderRequest request);

    Optional<TplOrder> find(long orderProductOptionNo);

    /** 출고 취소. 출고요청이면 무료, 출고작업(피킹 전)이면 수수료, 피킹 이후면 불가 (external-facts.md) */
    CancelResult cancel(long orderProductOptionNo);

    /** 출고 이벤트(상태 변경, 송장 발급)를 순번 이후로 가져온다 */
    EventPage events(long afterSeq, int limit);

    enum Status {
        /** 출고요청 */
        REQUESTED,
        /** 출고작업 (피킹 전) */
        WORKING,
        /** 피킹 */
        PICKED,
        /** 출고완료 */
        RELEASED,
        /** 취소됨 */
        CANCELED
    }

    record OrderRequest(long orderProductOptionNo, String orderNo, Long deliveryNo, String productName,
                        String optionName, String optionValue, int quantity) {
    }

    /**
     * @param accepted   등록됐는지
     * @param status     등록된 경우 현재 상태
     * @param reasonCode 거절 사유 (예: OPTION_NOT_MAPPED)
     */
    record Registration(boolean accepted, Status status, String reasonCode, String detail) {
    }

    record TplOrder(long orderProductOptionNo, Status status, String invoiceNo, String deliveryCompanyType) {
    }

    /**
     * @param canceled 취소됐는지
     * @param fee      취소 수수료(원)
     * @param status   취소하지 못한 경우 현재 상태
     */
    record CancelResult(boolean canceled, int fee, Status status) {
    }

    /** type: STATUS_CHANGED 또는 INVOICE_ISSUED */
    record Event(long seq, String type, long orderProductOptionNo, Status status, String invoiceNo,
                 String deliveryCompanyType) {
    }

    record EventPage(List<Event> events, long lastSeq) {
    }

    class TplException extends RuntimeException {
        public TplException(String message) {
            super(message);
        }
    }
}
