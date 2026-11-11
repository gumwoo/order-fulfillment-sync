package io.github.gumwoo.fulfillment.worker.message;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/** 경로(source)·결과(outcome)별 처리 건수. 측정과 시나리오 테스트에서 읽는다. */
@Component
public class ProcessingStats {

    private final Map<String, LongAdder> counts = new ConcurrentHashMap<>();

    public void record(String source, String outcome) {
        counts.computeIfAbsent(source + ":" + outcome, k -> new LongAdder()).increment();
    }

    public long count(String source, String outcome) {
        LongAdder a = counts.get(source + ":" + outcome);
        return a == null ? 0 : a.sum();
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> m = new TreeMap<>();
        counts.forEach((k, v) -> m.put(k, v.sum()));
        return m;
    }

    public void reset() {
        counts.clear();
    }
}
