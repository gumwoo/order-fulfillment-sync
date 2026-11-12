package io.github.gumwoo.fulfillment.infra.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderLineStateRepository extends JpaRepository<OrderLineStateEntity, Long> {

    List<OrderLineStateEntity> findByDeliveryNo(Long deliveryNo);

    /** 이미출고 성공 반영 (ADR-0012): 이 클레임일 때만 클레임을 지우고 배송 전 상태면 배송중으로. 버전을 올린다 */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "UPDATE order_line_state SET claim_status = NULL, claim_no = NULL,"
            + " status = CASE WHEN status IN ('DEPOSIT_WAIT', 'PAY_DONE', 'PRODUCT_PREPARE', 'DELIVERY_PREPARE')"
            + " THEN 'DELIVERY_ING' ELSE status END,"
            + " invoice_no = COALESCE(:invoiceNo, invoice_no), delivery_company_type = COALESCE(:company, delivery_company_type),"
            + " updated_at = :at, version = version + 1"
            + " WHERE order_product_option_no = :no AND claim_no = :claimNo", nativeQuery = true)
    int applyAlreadyDelivery(@org.springframework.data.repository.query.Param("no") long no,
                             @org.springframework.data.repository.query.Param("claimNo") long claimNo,
                             @org.springframework.data.repository.query.Param("company") String company,
                             @org.springframework.data.repository.query.Param("invoiceNo") String invoiceNo,
                             @org.springframework.data.repository.query.Param("at") java.time.LocalDateTime at);
}
