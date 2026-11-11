package io.github.gumwoo.fulfillment.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MismatchRepository extends JpaRepository<MismatchEntity, Long> {

    long countByRunId(String runId);
}
