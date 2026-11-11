package io.github.gumwoo.fulfillment.infra.shopby;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param baseUrl          샵바이 서버 API 주소 (로컬·테스트는 mock-servers)
 * @param systemKey        systemKey 헤더
 * @param accessToken      Authorization: Bearer 토큰
 * @param bucketCapacity   토큰 버킷 크기
 * @param refillPerSecond  초당 충전량. 어떤 1초 구간에서도 capacity + refill 이하로 나간다 (ADR-0007: 10 + 70 = 80)
 * @param maxWaitMs        토큰을 기다리는 최대 시간. 넘으면 RateLimitedException (재발행)
 * @param exhaustedPauseMs EXHAUSTED·429를 받은 뒤 모든 인스턴스가 호출을 멈추는 시간
 * @param timeoutMs        HTTP 연결·읽기 타임아웃
 */
@ConfigurationProperties("app.shopby")
public record ShopbyProperties(
        String baseUrl,
        String systemKey,
        String accessToken,
        int bucketCapacity,
        int refillPerSecond,
        long maxWaitMs,
        long exhaustedPauseMs,
        long timeoutMs) {

    public ShopbyProperties {
        if (bucketCapacity <= 0) {
            bucketCapacity = 10;
        }
        if (refillPerSecond <= 0) {
            refillPerSecond = 70;
        }
        if (maxWaitMs <= 0) {
            maxWaitMs = 2000;
        }
        if (exhaustedPauseMs <= 0) {
            exhaustedPauseMs = 1000;
        }
        if (timeoutMs <= 0) {
            timeoutMs = 5000;
        }
    }
}
