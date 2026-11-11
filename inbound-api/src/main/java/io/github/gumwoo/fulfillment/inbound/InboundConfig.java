package io.github.gumwoo.fulfillment.inbound;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InboundConfig {

    /** 샵바이 시각은 한국 시간 기준 LocalDateTime이다 */
    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
