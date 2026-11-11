package io.github.gumwoo.fulfillment.infra.persistence;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminActionRepository extends JpaRepository<AdminActionEntity, Long> {

    Optional<AdminActionEntity> findByRequestId(String requestId);

    /** 같은 요청 ID가 이미 있으면 0 (UNIQUE + INSERT IGNORE). 동시에 두 번 눌러도 하나만 1을 받는다. */
    @Modifying
    @Query(value = "INSERT IGNORE INTO admin_action (request_id, actor, action, target_key, status, created_at) "
            + "VALUES (:requestId, :actor, :action, :targetKey, 'IN_PROGRESS', :at)", nativeQuery = true)
    int insertIgnore(@Param("requestId") String requestId, @Param("actor") String actor, @Param("action") String action,
                     @Param("targetKey") String targetKey, @Param("at") LocalDateTime at);
}
