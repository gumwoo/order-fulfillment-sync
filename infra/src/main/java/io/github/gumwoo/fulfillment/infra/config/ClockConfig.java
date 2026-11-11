package io.github.gumwoo.fulfillment.infra.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 샵바이 시각은 한국 시간 기준 LocalDateTime(`yyyy-MM-dd HH:mm:ss`)이다. */
@Configuration
public class ClockConfig {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.system(ZONE);
    }
}
