package io.github.gumwoo.fulfillment.mock;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;

/**
 * 샵바이 서버 API mock과 파스토 규칙 3PL mock. 실제 샵바이 계정 없이 공개 스펙(external-facts.md)대로 동작한다.
 * 테스트·측정용 제어 API는 {@code /_mock/**} 아래에 있다.
 */
@SpringBootApplication
public class MockServersApplication {

    public static final String CONFIG_NAME = "mock-servers";

    public static void main(String[] args) {
        new SpringApplicationBuilder(MockServersApplication.class)
                .properties("spring.config.name=" + CONFIG_NAME)
                .run(args);
    }

    @Bean
    Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
