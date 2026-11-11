package io.github.gumwoo.fulfillment.infra.persistence;

import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEventEntity, String> {

    /** 이미 있으면 0, 새로 넣으면 1. UNIQUE 위반 예외로 트랜잭션이 깨지지 않게 INSERT IGNORE를 쓴다. */
    @Modifying
    @Query(value = "INSERT IGNORE INTO processed_event (idem_key, source, processed_at) VALUES (:key, :source, :at)",
            nativeQuery = true)
    int insertIgnore(@Param("key") String key, @Param("source") String source, @Param("at") LocalDateTime at);
}
