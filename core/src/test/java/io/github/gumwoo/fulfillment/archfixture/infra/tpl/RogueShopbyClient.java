package io.github.gumwoo.fulfillment.archfixture.infra.tpl;

import io.github.gumwoo.fulfillment.core.port.ShopbyClient;

/** 위반 픽스처: infra.shopby 밖에 만든 두 번째 ShopbyClient 구현체 (레이트 리미터를 거치지 않는 출구). */
public class RogueShopbyClient implements ShopbyClient {
}
