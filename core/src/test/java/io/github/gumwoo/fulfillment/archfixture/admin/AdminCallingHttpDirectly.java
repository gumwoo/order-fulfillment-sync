package io.github.gumwoo.fulfillment.archfixture.admin;

import java.net.http.HttpClient;

/** 위반 픽스처: 실행 모듈이 HTTP 클라이언트를 직접 쓴다. */
public class AdminCallingHttpDirectly {
    public HttpClient client() {
        return HttpClient.newHttpClient();
    }
}
