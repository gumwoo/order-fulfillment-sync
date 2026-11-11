package io.github.gumwoo.fulfillment.infra.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEntity, Long> {

    /**
     * PENDING 행을 잠그고 가져온다. 다른 트랜잭션이 잠근 행은 건너뛴다 (MySQL 8 SKIP LOCKED).
     * 호출자 트랜잭션이 끝날 때까지 잠금이 유지된다.
     */
    @Query(value = "SELECT * FROM outbox WHERE status = 'PENDING' ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<OutboxEntity> lockPending(@Param("limit") int limit);

    long countByStatus(String status);
}
