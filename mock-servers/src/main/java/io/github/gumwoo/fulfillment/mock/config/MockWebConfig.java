package io.github.gumwoo.fulfillment.mock.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 데모 화면(다른 출처)에서 mock 제어 API를 부를 수 있게 한다. 로컬 데모 전용 mock이다. */
@Configuration
public class MockWebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/_mock/**").allowedOrigins("http://localhost:5173", "http://localhost:8083")
                .allowedMethods("GET", "POST");
    }
}
