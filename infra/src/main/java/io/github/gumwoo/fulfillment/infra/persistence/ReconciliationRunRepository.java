package io.github.gumwoo.fulfillment.infra.persistence;

import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRunEntity, String> {

    List<ReconciliationRunEntity> findByOrderByRanAtDesc(Limit limit);
}
