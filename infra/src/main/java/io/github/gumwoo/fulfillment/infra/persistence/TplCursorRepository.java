package io.github.gumwoo.fulfillment.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TplCursorRepository extends JpaRepository<TplCursorEntity, String> {
}
