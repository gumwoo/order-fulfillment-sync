package io.github.gumwoo.fulfillment.archfixture.reconciler;

import io.github.gumwoo.fulfillment.archfixture.infra.shopby.ShopbyHttpCaller;

/** 위반 픽스처: 실행 모듈이 포트 대신 샵바이 어댑터 내부를 직접 쓴다. */
public class ReconcilerBypassingShopbyClient {
    public String poll() {
        return new ShopbyHttpCaller().get("/orders");
    }
}
