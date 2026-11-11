package io.github.gumwoo.fulfillment.core.order;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 주문상품 옵션 하나의 상태 변경. 웹훅(CHANGE_ORDER_STATUS 원소)과 주문 조회 결과가 같은 형태로 바뀐다.
 * 웹훅에는 이벤트 ID와 발생 시각이 없으므로 단계별 시각만 가진다 (external-facts.md).
 */
public record OrderStatusChange(
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
        StageTimes stageTimes) {

    public OrderStatusChange {
        Objects.requireNonNull(orderStatus, "orderStatus");
        Objects.requireNonNull(stageTimes, "stageTimes");
    }

    /** 단계별 시각 (모두 nullable) */
    public record StageTimes(
            LocalDateTime orderYmdt,
            LocalDateTime payYmdt,
            LocalDateTime orderAcceptYmdt,
            LocalDateTime releaseReadyYmdt,
            LocalDateTime releaseYmdt,
            LocalDateTime deliveryCompleteYmdt,
            LocalDateTime buyConfirmYmdt) {

        /** 가장 늦은 단계 시각. 같은 상태끼리 어느 쪽이 최신인지 판단할 때 쓴다 (ADR-0003). */
        public LocalDateTime latest() {
            return Stream.of(orderYmdt, payYmdt, orderAcceptYmdt, releaseReadyYmdt, releaseYmdt,
                            deliveryCompleteYmdt, buyConfirmYmdt)
                    .filter(Objects::nonNull)
                    .max(LocalDateTime::compareTo)
                    .orElse(null);
        }
    }

    public boolean hasClaim() {
        return claimStatusType != null && !claimStatusType.isBlank();
    }

    /**
     * 멱등 키 (ADR-0001): sha256(orderProductOptionNo | orderStatusType | claimStatusType | claimNo | 가장 늦은 단계 시각).
     * 웹훅·실패 웹훅·재조회 어느 경로로 들어와도 같은 변경이면 같은 키가 나온다.
     */
    public String idempotencyKey() {
        String raw = orderProductOptionNo + "|" + orderStatus.name() + "|" + nullToEmpty(claimStatusType) + "|"
                + (claimNo == null ? "" : claimNo) + "|" + (stageTimes.latest() == null ? "" : stageTimes.latest());
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 없음", e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
