package io.github.gumwoo.fulfillment.infra.shopby;

import io.github.gumwoo.fulfillment.core.port.ShopbyClient.RateLimitedException;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 모든 인스턴스가 공유하는 Redis 토큰 버킷 (ADR-0007). 시각은 Redis TIME을 써서 인스턴스 간 시계 차이를 없앤다.
 * EXHAUSTED·429를 받으면 버킷을 비우고 pause 키로 일정 시간 모두 멈춘다.
 * Redis 장애 시에는 호출하지 않는다(보수적) → RateLimitedException.
 */
class RedisTokenBucketRateLimiter {

    static final String BUCKET_KEY = "shopby:ratelimit:bucket";
    static final String PAUSE_KEY = "shopby:ratelimit:pause-until";

    /** 반환: 0 = 토큰 획득, 양수 = 기다릴 ms, 음수 = pause 중(절댓값만큼 남음) */
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local pause = tonumber(redis.call('GET', KEYS[2]) or '0')
            if pause > now then return -(pause - now) end
            local cap = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local b = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(b[1])
            local ts = tonumber(b[2])
            if tokens == nil or ts == nil then tokens = cap; ts = now end
            tokens = math.min(cap, tokens + (now - ts) * rate / 1000)
            local result
            if tokens >= 1 then
              tokens = tokens - 1
              result = 0
            else
              result = math.ceil((1 - tokens) * 1000 / rate)
            end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
            redis.call('PEXPIRE', KEYS[1], 60000)
            return result
            """, Long.class);

    private static final DefaultRedisScript<Long> EXHAUST = new DefaultRedisScript<>("""
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            redis.call('HSET', KEYS[1], 'tokens', '0', 'ts', tostring(now))
            local untilMs = now + tonumber(ARGV[1])
            local cur = tonumber(redis.call('GET', KEYS[2]) or '0')
            if untilMs > cur then redis.call('SET', KEYS[2], tostring(untilMs), 'PX', tonumber(ARGV[1]) + 1000) end
            return untilMs
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ShopbyProperties props;

    RedisTokenBucketRateLimiter(StringRedisTemplate redis, ShopbyProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /** 토큰을 얻을 때까지 기다린다. maxWaitMs를 넘기면 RateLimitedException. */
    void acquire() {
        long deadline = System.currentTimeMillis() + props.maxWaitMs();
        while (true) {
            Long r;
            try {
                r = redis.execute(ACQUIRE, List.of(BUCKET_KEY, PAUSE_KEY),
                        String.valueOf(props.bucketCapacity()), String.valueOf(props.refillPerSecond()));
            } catch (RuntimeException e) {
                throw new RateLimitedException("레이트 리미터(Redis) 사용 불가, 호출하지 않음: " + e);
            }
            if (r != null && r == 0) {
                return;
            }
            long wait = r == null ? 50 : Math.abs(r);
            if (System.currentTimeMillis() + wait > deadline) {
                throw new RateLimitedException("샵바이 호출 대기 한도 초과 (" + (r != null && r < 0 ? "EXHAUSTED/429 일시정지" : "토큰 부족") + ")");
            }
            try {
                Thread.sleep(Math.max(wait, 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RateLimitedException("대기 중 인터럽트");
            }
        }
    }

    /** EXHAUSTED 헤더나 429를 받았다: 버킷을 비우고 잠시 모두 멈춘다 */
    void exhausted() {
        try {
            redis.execute(EXHAUST, List.of(BUCKET_KEY, PAUSE_KEY), String.valueOf(props.exhaustedPauseMs()));
        } catch (RuntimeException ignored) {
            // Redis 장애면 다음 acquire가 어차피 실패한다
        }
    }
}
