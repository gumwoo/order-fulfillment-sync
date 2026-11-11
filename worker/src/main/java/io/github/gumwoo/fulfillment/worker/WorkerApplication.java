package io.github.gumwoo.fulfillment.worker;

import io.github.gumwoo.fulfillment.infra.config.CoreServicesConfig;
import io.github.gumwoo.fulfillment.infra.config.ShopbyApiConfig;
import io.github.gumwoo.fulfillment.infra.sqs.SqsConfig;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/** SQS 소비, 상태 반영, 3PL 연동, 지연 재처리. */
@SpringBootApplication
@EnableScheduling
@Import({CoreServicesConfig.class, SqsConfig.class, ShopbyApiConfig.class})
public class WorkerApplication {

    public static final String CONFIG_NAME = "worker";

    public static void main(String[] args) {
        new SpringApplicationBuilder(WorkerApplication.class)
                .properties("spring.config.name=" + CONFIG_NAME)
                .run(args);
    }
}
