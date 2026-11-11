package io.github.gumwoo.fulfillment.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RecallTaskRepository extends JpaRepository<RecallTaskEntity, Long> {

    boolean existsByOrderProductOptionNoAndClaimNo(Long orderProductOptionNo, Long claimNo);
}
