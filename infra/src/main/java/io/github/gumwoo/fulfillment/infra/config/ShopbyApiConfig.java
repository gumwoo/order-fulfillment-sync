package io.github.gumwoo.fulfillment.infra.config;

import io.github.gumwoo.fulfillment.infra.shopby.ShopbyConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 실행 모듈은 infra.shopby를 직접 참조하지 않는다(ArchitectureTest). 이 설정을 @Import 하면 ShopbyClient 포트 빈이 생긴다.
 */
@Configuration
@Import(ShopbyConfig.class)
public class ShopbyApiConfig {
}
