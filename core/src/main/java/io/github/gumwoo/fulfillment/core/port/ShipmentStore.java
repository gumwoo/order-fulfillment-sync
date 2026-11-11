package io.github.gumwoo.fulfillment.core.port;

import io.github.gumwoo.fulfillment.core.port.TplClient.Status;
import java.time.LocalDateTime;
import java.util.Optional;

/** 출고(3PL) 쪽 상태 영속성 포트. 단계마다 표시해 같은 일을 두 번 하지 않는다. */
public interface ShipmentStore {

    record Shipment(long orderProductOptionNo, Long deliveryNo, Status tplStatus, boolean registered,
                    boolean prepared, String invoiceNo, String deliveryCompanyType) {
    }

    Optional<Shipment> find(long orderProductOptionNo);

    void markRegistered(long orderProductOptionNo, Long deliveryNo, Status status, LocalDateTime at);

    void markPrepared(long orderProductOptionNo, LocalDateTime at);

    void updateTplStatus(long orderProductOptionNo, Status status, LocalDateTime at);

    /** 열린(미해결) 미연동 건이 없을 때만 새로 기록한다 */
    void recordUnlinkedIfAbsent(long orderProductOptionNo, String reasonCode, String detail, LocalDateTime at);

    void resolveUnlinked(long orderProductOptionNo, String resolver, LocalDateTime at);
}
