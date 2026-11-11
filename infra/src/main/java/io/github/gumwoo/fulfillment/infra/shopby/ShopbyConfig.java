package io.github.gumwoo.fulfillment.infra.shopby;

import io.github.gumwoo.fulfillment.core.port.ShopbyClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** 실행 모듈이 @Import 해서 ShopbyClient 포트만 주입받는다. 내부 클래스는 이 패키지 밖에서 쓰지 않는다. */
@Configuration
@EnableConfigurationProperties(ShopbyProperties.class)
public class ShopbyConfig {

    @Bean
    public ShopbyClient shopbyClient(ShopbyProperties props, StringRedisTemplate redis) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.timeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(props.timeoutMs()));
        RestClient http = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .defaultHeader("systemKey", props.systemKey() == null ? "" : props.systemKey())
                .defaultHeader("Authorization", "Bearer " + (props.accessToken() == null ? "" : props.accessToken()))
                .build();
        return new RestClientShopbyClient(http, new RedisTokenBucketRateLimiter(redis, props));
    }
}
