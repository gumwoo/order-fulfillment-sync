package io.github.gumwoo.fulfillment.core.cancel;

/** 취소·출고 경합 처리 단계 (ADR-0005) */
public enum CancelState {
    /** 취소 신청을 받음 */
    RECEIVED,
    /** 출고작업 중이라 샵바이 배송보류를 기다림 (일괄 처리 대상) */
    HOLD_PENDING,
    /** 배송보류 완료, 3PL 취소 진행 */
    HELD,
    /** 배송보류가 건별 재시도까지 실패 (사람이 봐야 함) */
    HOLD_FAILED,
    /** 3PL 취소 완료, 샵바이 클레임 승인 진행 */
    TPL_CANCELED,
    /** 샵바이 취소 완료 */
    DONE,
    /** 피킹 이후라 취소 불가: 이미출고 처리 후 CS 회수 목록 */
    RECALL_REQUIRED
}
