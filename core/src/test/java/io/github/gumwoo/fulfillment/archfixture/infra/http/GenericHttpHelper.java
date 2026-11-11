package io.github.gumwoo.fulfillment.archfixture.infra.http;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;

/** 위반 픽스처: 어댑터 패키지 밖의 infra 범용 헬퍼가 JDK HttpURLConnection으로 직접 호출한다. */
public class GenericHttpHelper {
    public int get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        return connection.getResponseCode();
    }
}
