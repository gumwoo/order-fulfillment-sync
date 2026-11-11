package io.github.gumwoo.fulfillment.inbound;

import io.github.gumwoo.fulfillment.infra.config.PersistenceConfig;
import io.github.gumwoo.fulfillment.infra.sqs.SqsConfig;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 웹훅 수신: 원본 저장 후 즉시 200, outbox를 통해 SQS 발행 (설계 4.1). */
@SpringBootApplication
@EnableScheduling
@Import({PersistenceConfig.class, SqsConfig.class})
public class InboundApiApplication {

    /** 여러 실행 모듈이 한 클래스패스에 있어도 설정이 섞이지 않도록 설정 파일 이름을 모듈별로 둔다. */
    public static final String CONFIG_NAME = "inbound-api";

    public static void main(String[] args) {
        new SpringApplicationBuilder(InboundApiApplication.class)
                .properties("spring.config.name=" + CONFIG_NAME)
                .run(args);
    }
}
