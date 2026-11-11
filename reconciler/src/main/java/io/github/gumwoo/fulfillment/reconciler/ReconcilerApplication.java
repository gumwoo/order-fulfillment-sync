package io.github.gumwoo.fulfillment.reconciler;

import io.github.gumwoo.fulfillment.infra.config.CoreServicesConfig;
import io.github.gumwoo.fulfillment.infra.config.ShopbyApiConfig;
import io.github.gumwoo.fulfillment.infra.sqs.SqsConfig;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 복구 경로: 실패 웹훅 조회, STATUS_CHANGE 재조회, 대사 배치 (설계 4.4, ADR-0002). */
@SpringBootApplication
@EnableScheduling
@Import({CoreServicesConfig.class, SqsConfig.class, ShopbyApiConfig.class})
public class ReconcilerApplication {

    public static final String CONFIG_NAME = "reconciler";

    public static void main(String[] args) {
        new SpringApplicationBuilder(ReconcilerApplication.class)
                .properties("spring.config.name=" + CONFIG_NAME)
                .run(args);
    }
}
