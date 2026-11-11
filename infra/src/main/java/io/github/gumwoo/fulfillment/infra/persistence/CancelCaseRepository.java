package io.github.gumwoo.fulfillment.infra.persistence;

import io.github.gumwoo.fulfillment.core.cancel.CancelState;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CancelCaseRepository extends JpaRepository<CancelCaseEntity, Long> {

    Optional<CancelCaseEntity> findByOrderProductOptionNoAndClaimNo(Long orderProductOptionNo, Long claimNo);

    /** SELECT ... FOR UPDATE */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CancelCaseEntity c where c.orderProductOptionNo = :no and c.claimNo = :claimNo")
    Optional<CancelCaseEntity> lock(@Param("no") Long orderProductOptionNo, @Param("claimNo") Long claimNo);

    @Query(value = "SELECT * FROM cancel_case WHERE state = :state ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<CancelCaseEntity> lockByState(@Param("state") String state, @Param("limit") int limit);

    List<CancelCaseEntity> findByStateOrderByIdAsc(CancelState state, Limit limit);

    /** 동시에 같은 취소 메시지를 처리해도 한 건만 생기게 (UNIQUE + INSERT IGNORE) */
    @Modifying
    @Query(value = "INSERT IGNORE INTO cancel_case (order_product_option_no, claim_no, state, fee, created_at, updated_at, version) "
            + "VALUES (:optionNo, :claimNo, 'RECEIVED', 0, :at, :at, 0)", nativeQuery = true)
    int insertIgnore(@Param("optionNo") long optionNo, @Param("claimNo") long claimNo, @Param("at") LocalDateTime at);
}
