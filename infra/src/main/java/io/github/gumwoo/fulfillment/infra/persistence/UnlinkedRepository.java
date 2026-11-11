package io.github.gumwoo.fulfillment.infra.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UnlinkedRepository extends JpaRepository<UnlinkedEntity, Long> {

    Optional<UnlinkedEntity> findFirstByOrderProductOptionNoAndResolvedAtIsNull(Long orderProductOptionNo);

    List<UnlinkedEntity> findByOrderProductOptionNo(Long orderProductOptionNo);

    /** 미해결 건이 이미 있으면 무시 (uq_unlinked_open) */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "INSERT IGNORE INTO unlinked (order_product_option_no, reason_code, detail, detected_at, open_key) "
            + "VALUES (:no, :reason, :detail, :at, :no)", nativeQuery = true)
    int insertOpenIgnore(@org.springframework.data.repository.query.Param("no") long no,
                         @org.springframework.data.repository.query.Param("reason") String reason,
                         @org.springframework.data.repository.query.Param("detail") String detail,
                         @org.springframework.data.repository.query.Param("at") java.time.LocalDateTime at);
}
