package io.github.gumwoo.fulfillment.admin;

import io.github.gumwoo.fulfillment.infra.config.AdminQueryConfig;
import io.github.gumwoo.fulfillment.infra.config.ShipmentServicesConfig;
import io.github.gumwoo.fulfillment.infra.sqs.SqsConfig;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Import;

/** CS 어드민 API: 미연동·충돌·회수 목록, 재처리, 처리 이력 (설계 4.9), 데모 화면용 현황·대사 이력 (0002). 화면은 frontend/ 모듈이다. */
@SpringBootApplication
@Import({ShipmentServicesConfig.class, AdminQueryConfig.class, SqsConfig.class})
public class AdminApiApplication {

    public static final String CONFIG_NAME = "admin-api";

    public static void main(String[] args) {
        new SpringApplicationBuilder(AdminApiApplication.class)
                .properties("spring.config.name=" + CONFIG_NAME)
                .run(args);
    }
}
