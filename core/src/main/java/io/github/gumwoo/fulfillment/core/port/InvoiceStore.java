package io.github.gumwoo.fulfillment.core.port;

import io.github.gumwoo.fulfillment.core.port.TplClient.Status;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 3PL 출고 이벤트와 송장 반영 상태 영속성 포트 (F-11, F-12) */
public interface InvoiceStore {

    /** 샵바이에 보낼 송장: 배송번호 단위 (by-shipping-no) */
    record PendingInvoice(long shippingNo, String deliveryCompanyType, String invoiceNo) {
    }

    record ShipmentView(long orderProductOptionNo, Long deliveryNo, Status tplStatus, String invoiceNo) {
    }

    Optional<ShipmentView> find(long orderProductOptionNo);

    /** 3PL 상태는 앞 단계로만 바뀐다 (늦게 처리된 이벤트가 되돌리지 않게). CANCELED는 끝 상태. */
    void updateTplStatus(long orderProductOptionNo, Status status, LocalDateTime at);

    void saveInvoice(long orderProductOptionNo, String invoiceNo, String deliveryCompanyType, LocalDateTime at);

    /**
     * 보낼 배송을 잠그고 가져온다 (호출자 트랜잭션 안에서, 다른 인스턴스가 잠근 배송은 건너뛴다).
     * 배송의 모든 옵션(취소 완료 제외)이 출고완료이고 송장이 있으며 송장이 하나로 같을 때만 대상이다.
     * 옵션별 송장이 다르면 conflict를 남기고 제외한다. 실패가 maxFailures 이상인 배송은 제외한다.
     */
    List<PendingInvoice> lockPending(int limit, int maxFailures);

    void markSynced(long shippingNo, LocalDateTime at);

    /** @return 이 배송의 누적 실패 횟수 */
    int markSyncFailed(long shippingNo, LocalDateTime at);

    long getCursor(String name);

    void saveCursor(String name, long seq);
}
