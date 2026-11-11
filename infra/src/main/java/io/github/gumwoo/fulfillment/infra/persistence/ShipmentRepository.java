package io.github.gumwoo.fulfillment.infra.persistence;

import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ShipmentRepository extends JpaRepository<ShipmentEntity, Long> {

    /** 출고완료이고 송장이 있으며 아직 샵바이에 반영하지 않은 출고 (F-11, F-12). 실패 횟수가 적은 것부터 */
    @Query("select s from ShipmentEntity s where s.tplStatus = io.github.gumwoo.fulfillment.core.port.TplClient.Status.RELEASED"
            + " and s.invoiceNo is not null and s.invoiceSyncedAt is null and s.deliveryNo is not null"
            + " order by s.invoiceSyncFailures, s.deliveryNo")
    List<ShipmentEntity> findReleasedInvoicePending(Limit limit);

    List<ShipmentEntity> findByDeliveryNo(Long deliveryNo);

    /** 같은 옵션을 두 소비자가 동시에 처음 처리해도 PK 충돌 없이 행 하나만 생기게 */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "INSERT IGNORE INTO shipment (order_product_option_no, invoice_sync_failures, updated_at, version) "
            + "VALUES (:no, 0, :at, 0)", nativeQuery = true)
    int insertIgnore(@org.springframework.data.repository.query.Param("no") long no,
                     @org.springframework.data.repository.query.Param("at") java.time.LocalDateTime at);
}
