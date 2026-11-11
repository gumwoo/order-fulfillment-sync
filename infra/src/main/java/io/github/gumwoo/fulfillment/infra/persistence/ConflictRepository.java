package io.github.gumwoo.fulfillment.infra.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConflictRepository extends JpaRepository<ConflictEntity, Long> {

    List<ConflictEntity> findByTargetKey(String targetKey);

    boolean existsByTargetKeyAndReasonCodeAndResolvedAtIsNull(String targetKey, String reasonCode);
}
