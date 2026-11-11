package io.github.gumwoo.fulfillment.infra.tpl;

import io.github.gumwoo.fulfillment.core.port.TplClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class TplConfig {

    @Bean
    public TplClient tplClient(@Value("${app.tpl.base-url}") String baseUrl,
                               @Value("${app.tpl.timeout-ms:5000}") long timeoutMs) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofMillis(timeoutMs));
        f.setReadTimeout(Duration.ofMillis(timeoutMs));
        return new RestClientTplClient(RestClient.builder().baseUrl(baseUrl).requestFactory(f).build());
    }
}
