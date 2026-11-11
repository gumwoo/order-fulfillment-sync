package io.github.gumwoo.fulfillment.infra.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRepository extends JpaRepository<AlertEntity, Long> {

    List<AlertEntity> findByNotifiedAtIsNullOrderByIdAsc();

    List<AlertEntity> findByType(String type);

    boolean existsByTypeAndTargetKeyAndCreatedAtAfter(String type, String targetKey, java.time.LocalDateTime after);
}
