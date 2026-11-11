package io.github.gumwoo.fulfillment.infra.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** 실행 모듈이 @Import 해서 infra의 JPA 엔티티와 리포지토리를 쓴다. */
@Configuration
@EntityScan("io.github.gumwoo.fulfillment.infra.persistence")
@EnableJpaRepositories("io.github.gumwoo.fulfillment.infra.persistence")
public class PersistenceConfig {
}
