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

    void updateTplStatus(long orderProductOptionNo, Status status, LocalDateTime at);

    void saveInvoice(long orderProductOptionNo, String invoiceNo, String deliveryCompanyType, LocalDateTime at);

    /** 출고완료이고 송장이 있으며 아직 샵바이에 반영하지 않은 배송 (배송번호로 묶음) */
    List<PendingInvoice> findPending(int limit);

    void markSynced(long shippingNo, LocalDateTime at);

    void markSyncFailed(long shippingNo, LocalDateTime at);

    long getCursor(String name);

    void saveCursor(String name, long seq);
}
