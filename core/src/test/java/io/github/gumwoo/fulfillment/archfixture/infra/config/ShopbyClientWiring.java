package io.github.gumwoo.fulfillment.archfixture.infra.config;

import io.github.gumwoo.fulfillment.archfixture.infra.shopby.FixtureShopbyClientAdapter;
import io.github.gumwoo.fulfillment.core.port.ShopbyClient;

/** 허용 픽스처: 포트 구현체만 생성해 노출한다. */
public class ShopbyClientWiring {
    public ShopbyClient shopbyClient() {
        return new FixtureShopbyClientAdapter();
    }
}
