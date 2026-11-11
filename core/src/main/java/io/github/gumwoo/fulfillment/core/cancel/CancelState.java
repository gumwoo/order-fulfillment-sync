package io.github.gumwoo.fulfillment.core.cancel;

/** 취소·출고 경합 처리 단계 (ADR-0005, ADR-0009) */
public enum CancelState {
    /** 취소 신청을 받음 */
    RECEIVED,
    /** 출고작업 중이라 샵바이 배송보류를 기다림 (일괄 처리 대상) */
    HOLD_PENDING,
    /** 배송보류 완료, 3PL 취소 진행 */
    HELD,
    /** 배송보류가 건별 재시도에서 거절됐고 3PL은 아직 피킹 전 (사람이 봐야 함, conflict) */
    HOLD_FAILED,
    /** 3PL 취소 완료, 샵바이 클레임 승인 진행 */
    TPL_CANCELED,
    /** 샵바이 취소 완료 */
    DONE,
    /** 피킹 이후라 취소 불가: CS 회수 건을 먼저 남겼고 샵바이 이미출고 처리 진행 */
    RECALL_PENDING,
    /** 이미출고 처리 끝, CS 회수 안내 필요 */
    RECALL_REQUIRED,
    /** 저장하지 않는 결과: 현재 클레임이 아니라 처리하지 않음 (늦게 온 취소 신청 등) */
    IGNORED;

    public boolean isWaitingOrTerminal() {
        return this == HOLD_PENDING || this == HOLD_FAILED || this == DONE || this == RECALL_REQUIRED || this == IGNORED;
    }
}
