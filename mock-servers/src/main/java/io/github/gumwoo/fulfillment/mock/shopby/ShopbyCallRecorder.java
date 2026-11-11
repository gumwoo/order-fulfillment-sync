package io.github.gumwoo.fulfillment.mock.shopby;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * mock 샵바이 서버 API 호출 기록과 호출 제한 흉내 (external-facts.md: 초당 100회 토큰 버킷, 초과 시 429,
 * 헤더 ratelimit-available-level: EXHAUSTED).
 * <ul>
 *   <li>경로별 호출 수, 초(epoch second)별 호출 수를 센다 → 측정과 F-07 검증에 쓴다.</li>
 *   <li>장애 주입: 다음 N번 호출에 429, 다음 N번 응답에 EXHAUSTED 헤더.</li>
 *   <li>실제 한도 흉내: 초당 limitPerSecond를 넘으면 429 (0이면 끔).</li>
 * </ul>
 * {@code /_mock/**}(제어 API)는 세지 않는다.
 */
@Component
public class ShopbyCallRecorder extends OncePerRequestFilter {

    private final Map<String, AtomicLong> byPath = new ConcurrentHashMap<>();
    private final Map<Long, AtomicInteger> bySecond = new ConcurrentHashMap<>();
    private final AtomicInteger force429 = new AtomicInteger();
    private final AtomicInteger forceExhausted = new AtomicInteger();
    private final AtomicLong rejected429 = new AtomicLong();
    private volatile int limitPerSecond = 0;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/_mock") || uri.startsWith("/tpl") || uri.startsWith("/error");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        long second = System.currentTimeMillis() / 1000;
        int inThisSecond = bySecond.computeIfAbsent(second, k -> new AtomicInteger()).incrementAndGet();
        byPath.computeIfAbsent(req.getMethod() + " " + normalize(req.getRequestURI()), k -> new AtomicLong()).incrementAndGet();
        boolean overLimit = limitPerSecond > 0 && inThisSecond > limitPerSecond;
        if (overLimit || decrementIfPositive(force429)) {
            rejected429.incrementAndGet();
            res.setStatus(429);
            res.setHeader("ratelimit-available-level", "EXHAUSTED");
            res.setContentType("application/json");
            res.getWriter().write("{\"code\":\"TOO_MANY_REQUESTS\",\"message\":\"mock 429\"}");
            return;
        }
        if (decrementIfPositive(forceExhausted)) {
            res.setHeader("ratelimit-available-level", "EXHAUSTED");
        }
        chain.doFilter(req, res);
    }

    private static boolean decrementIfPositive(AtomicInteger a) {
        while (true) {
            int v = a.get();
            if (v <= 0) {
                return false;
            }
            if (a.compareAndSet(v, v - 1)) {
                return true;
            }
        }
    }

    private static String normalize(String uri) {
        return uri.replaceAll("/claims/\\d+/", "/claims/{claimNo}/");
    }

    public void reset() {
        byPath.clear();
        bySecond.clear();
        force429.set(0);
        forceExhausted.set(0);
        rejected429.set(0);
        limitPerSecond = 0;
    }

    public void inject(int next429, int nextExhausted, int limitPerSecond) {
        force429.set(next429);
        forceExhausted.set(nextExhausted);
        this.limitPerSecond = limitPerSecond;
    }

    public Map<String, Long> callsByPath() {
        Map<String, Long> m = new TreeMap<>();
        byPath.forEach((k, v) -> m.put(k, v.get()));
        return m;
    }

    public int maxCallsInAnySecond() {
        return bySecond.values().stream().mapToInt(AtomicInteger::get).max().orElse(0);
    }

    public long rejected429() {
        return rejected429.get();
    }
}
