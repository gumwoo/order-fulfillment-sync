package io.github.gumwoo.fulfillment.infra.config;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.github.gumwoo.fulfillment.infra.query.AdminQueryRepository;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** CS 어드민 조회용 QueryDSL 설정. admin-api가 @Import 한다. */
@Configuration
@Import(PersistenceConfig.class)
public class AdminQueryConfig {

    @Bean
    public JPAQueryFactory jpaQueryFactory(EntityManager em) {
        return new JPAQueryFactory(em);
    }

    @Bean
    public AdminQueryRepository adminQueryRepository(JPAQueryFactory query) {
        return new AdminQueryRepository(query);
    }
}
