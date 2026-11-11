package io.github.gumwoo.fulfillment.core.port;

import io.github.gumwoo.fulfillment.core.order.OrderStatusChange;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 샵바이 서버 API 호출의 유일한 출구. 구현은 infra.shopby에 두고 전역 레이트 리미터를 거친다 (ADR-0007).
 * 요청·응답 형태는 external-facts.md에 확인한 것만 쓴다.
 *
 * <p>호출이 레이트 리미터 대기 한도를 넘거나 429를 받으면 {@link RateLimitedException},
 * 그 외 2xx가 아니면 {@link ApiException}을 던진다.
 */
public interface ShopbyClient {

    /** {@code GET /orders} v1.1, searchDateType=STATUS_CHANGE. 주문옵션 단위로 펼쳐서 돌려준다. */
    OrderSearchPage searchOrdersByStatusChange(LocalDateTime start, LocalDateTime end, int pageNumber, int pageSize);

    /** {@code GET /webhooks/failed}. 검색 기간은 최대 7일. */
    FailedWebhookPage searchFailedWebhooks(LocalDateTime start, LocalDateTime end, int page, int pageSize);

    /** {@code PUT /orders/prepare-product}. 하나라도 실패하면 전부 미반영. */
    void prepareProduct(List<Long> orderOptionNos);

    /** {@code PUT /orders/hold-delivery}. 최대 1,000건, 하나라도 실패하면 전부 미반영. */
    void holdDelivery(List<Long> orderOptionNos, String reason);

    /** {@code PUT /orders/change-status/by-shipping-no}. 최대 100건, 부분 실패. */
    InvoiceChangeResult changeStatusByShippingNo(String orderStatusType, List<InvoiceChange> changes);

    /** {@code PUT /claims/{claimNo}/approve}. 주문 취소는 호출 성공 즉시 완료. */
    void approveClaim(long claimNo);

    /** {@code PUT /claims/{claimNo}/already-delivery}. 클레임 철회 후 배송중으로 변경. */
    void alreadyDelivery(long claimNo, String deliveryCompanyType, String invoiceNo, LocalDate releaseYmd);

    /**
     * @param totalCount 조건에 맞는 전체 주문 수
     * @param orderCount 이 페이지의 주문 수 (페이지 크기와 비교해 다음 페이지 여부를 판단)
     * @param options    이 페이지 주문들의 주문옵션
     */
    record OrderSearchPage(int totalCount, int orderCount, List<OrderStatusChange> options) {
    }

    record FailedWebhookPage(int totalCount, List<FailedWebhook> contents) {
    }

    /** data 필드는 형식이 확인되지 않아 파싱하지 않는다 (external-facts.md "확인하지 못한 것") */
    record FailedWebhook(String eventType, LocalDateTime exceptionDateTime, String data) {
    }

    record InvoiceChange(long shippingNo, String deliveryCompanyType, String invoiceNo) {
    }

    record InvoiceChangeResult(int totalCount, int successCount, int failCount, List<InvoiceFailure> failures) {
    }

    record InvoiceFailure(InvoiceChange originalRequest, String errorCode, String errorMessage) {
    }

    /** 레이트 리미터 대기 한도 초과, 429, Redis 장애로 지금 호출할 수 없음. 재발행으로 다시 시도한다 (ADR-0007). */
    class RateLimitedException extends RuntimeException {
        public RateLimitedException(String message) {
            super(message);
        }
    }

    class ApiException extends RuntimeException {
        private final int status;

        public ApiException(int status, String message) {
            super("샵바이 API " + status + ": " + message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
