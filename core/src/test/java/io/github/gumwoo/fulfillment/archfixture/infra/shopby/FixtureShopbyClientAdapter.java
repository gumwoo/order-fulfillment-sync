package io.github.gumwoo.fulfillment.archfixture.infra.shopby;

import io.github.gumwoo.fulfillment.core.port.ShopbyClient;

/** 허용 픽스처: 포트 구현체. infra.shopby 밖에서 써도 된다. */
public class FixtureShopbyClientAdapter implements ShopbyClient {
    private final ShopbyHttpCaller caller = new ShopbyHttpCaller();

    public String call(String path) {
        return caller.get(path);
    }
}
