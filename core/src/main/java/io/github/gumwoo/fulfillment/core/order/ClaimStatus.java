package io.github.gumwoo.fulfillment.core.order;

/**
 * 취소 클레임 상태 (external-facts.md). 클레임 축의 진행 순서만 판단한다.
 * 반품·교환은 범위 밖이라 순위를 매기지 않는다(같은 순위로 본다).
 */
public final class ClaimStatus {

    public static final String CANCEL_REQUEST = "CANCEL_REQUEST";
    public static final String CANCEL_PROC_REQUEST_REFUND = "CANCEL_PROC_REQUEST_REFUND";
    public static final String CANCEL_PROC_WAITING_REFUND = "CANCEL_PROC_WAITING_REFUND";
    public static final String CANCEL_DONE = "CANCEL_DONE";
    public static final String CANCEL_NO_REFUND = "CANCEL_NO_REFUND";

    private ClaimStatus() {
    }

    public static int rank(String claimStatusType) {
        if (claimStatusType == null) {
            return 0;
        }
        return switch (claimStatusType) {
            case CANCEL_REQUEST -> 1;
            case CANCEL_PROC_REQUEST_REFUND, CANCEL_PROC_WAITING_REFUND -> 2;
            case CANCEL_DONE, CANCEL_NO_REFUND -> 3;
            default -> 1;
        };
    }

    public static boolean isCancelDone(String claimStatusType) {
        return CANCEL_DONE.equals(claimStatusType) || CANCEL_NO_REFUND.equals(claimStatusType);
    }
}
