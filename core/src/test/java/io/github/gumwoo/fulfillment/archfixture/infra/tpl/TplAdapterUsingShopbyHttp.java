package io.github.gumwoo.fulfillment.archfixture.infra.tpl;

import io.github.gumwoo.fulfillment.archfixture.infra.shopby.ShopbyHttpCaller;

/** 위반 픽스처: infra의 다른 어댑터가 샵바이 내부 호출 클래스를 직접 쓴다 (레이트 리미터 우회). */
public class TplAdapterUsingShopbyHttp {
    public String sync() {
        return new ShopbyHttpCaller().get("/orders");
    }
}
