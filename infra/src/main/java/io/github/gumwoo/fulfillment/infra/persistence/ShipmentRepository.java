package io.github.gumwoo.fulfillment.infra.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ShipmentRepository extends JpaRepository<ShipmentEntity, Long> {

    List<ShipmentEntity> findByDeliveryNo(Long deliveryNo);

    /** 송장 반영 후보 배송번호 (출고완료·송장 있음·미반영·실패 한도 미만인 옵션이 하나라도 있는 배송) */
    @Query(value = "SELECT delivery_no FROM shipment WHERE tpl_status = 'RELEASED' AND invoice_no IS NOT NULL"
            + " AND invoice_synced_at IS NULL AND delivery_no IS NOT NULL AND invoice_sync_failures < :maxFailures"
            + " GROUP BY delivery_no ORDER BY MIN(invoice_sync_failures), delivery_no LIMIT :limit", nativeQuery = true)
    List<Long> findCandidateDeliveryNos(@org.springframework.data.repository.query.Param("maxFailures") int maxFailures,
                                        @org.springframework.data.repository.query.Param("limit") int limit);

    /** 배송의 출고 행을 잠근다. 다른 트랜잭션이 잠근 행은 건너뛴다 */
    @Query(value = "SELECT * FROM shipment WHERE delivery_no = :deliveryNo FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<ShipmentEntity> lockByDeliveryNo(@org.springframework.data.repository.query.Param("deliveryNo") Long deliveryNo);

    /** 같은 옵션을 두 소비자가 동시에 처음 처리해도 PK 충돌 없이 행 하나만 생기게 */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "INSERT IGNORE INTO shipment (order_product_option_no, invoice_sync_failures, updated_at, version) "
            + "VALUES (:no, 0, :at, 0)", nativeQuery = true)
    int insertIgnore(@org.springframework.data.repository.query.Param("no") long no,
                     @org.springframework.data.repository.query.Param("at") java.time.LocalDateTime at);
}
