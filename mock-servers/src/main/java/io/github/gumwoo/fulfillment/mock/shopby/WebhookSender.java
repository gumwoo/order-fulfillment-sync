package io.github.gumwoo.fulfillment.mock.shopby;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 웹훅 발송 흉내. 샵바이는 실패한 웹훅을 재전송하지 않고 실패 내역만 남긴다 (external-facts.md).
 * 장애 주입: 일정 비율 버리기(실패 내역으로 남기거나 흔적 없이), 같은 웹훅 여러 번 보내기.
 */
@Component
public class WebhookSender {

    /**
     * @param url                   수신 URL (비우면 보내지 않음)
     * @param dropRatio             0~1, 이 비율만큼 보내지 않는다
     * @param recordDroppedAsFailed 버린 웹훅을 실패 내역(GET /webhooks/failed)에 남길지
     * @param duplicates            같은 웹훅을 몇 번 보낼지 (1 = 정상)
     * @param seed                  재현 가능한 난수
     */
    public record Config(String url, double dropRatio, boolean recordDroppedAsFailed, int duplicates, long seed) {
        static Config disabled() {
            return new Config(null, 0, false, 1, 1);
        }
    }

    /** 실패 내역 (GET /webhooks/failed 응답 contents 원소) */
    public record FailedRecord(String eventType, LocalDateTime exceptionDateTime, String data, String webhookUrl) {
    }

    private final ObjectMapper mapper;
    private final RestClient http;
    private volatile Config config = Config.disabled();
    private volatile Random random = new Random(1);
    private final List<FailedRecord> failed = new ArrayList<>();
    final AtomicLong sent = new AtomicLong();
    final AtomicLong dropped = new AtomicLong();

    public WebhookSender(ObjectMapper mapper) {
        this.mapper = mapper;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(3));
        f.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().requestFactory(f).build();
    }

    public synchronized void configure(Config c) {
        this.config = c;
        this.random = new Random(c.seed());
    }

    public synchronized void reset() {
        configure(Config.disabled());
        failed.clear();
        sent.set(0);
        dropped.set(0);
    }

    /** CHANGE_ORDER_STATUS 배열 하나를 보낸다 */
    public void send(List<ObjectNode> elements, LocalDateTime now) {
        Config c = config;
        if (c.url() == null || c.url().isBlank() || elements.isEmpty()) {
            return;
        }
        ArrayNode body = mapper.createArrayNode();
        elements.forEach(body::add);
        String json = body.toString();
        boolean drop;
        synchronized (this) {
            drop = random.nextDouble() < c.dropRatio();
        }
        if (drop) {
            dropped.incrementAndGet();
            if (c.recordDroppedAsFailed()) {
                recordFailed(json, now, c.url());
            }
            return;
        }
        for (int i = 0; i < Math.max(1, c.duplicates()); i++) {
            try {
                http.post().uri(c.url()).contentType(MediaType.APPLICATION_JSON).body(json).retrieve().toBodilessEntity();
                sent.incrementAndGet();
            } catch (RuntimeException e) {
                recordFailed(json, now, c.url());
            }
        }
    }

    private synchronized void recordFailed(String json, LocalDateTime at, String url) {
        failed.add(new FailedRecord("CHANGE_ORDER_STATUS", at, json, url));
    }

    public synchronized List<FailedRecord> failedBetween(LocalDateTime start, LocalDateTime end) {
        return failed.stream()
                .filter(f -> !f.exceptionDateTime().isBefore(start) && !f.exceptionDateTime().isAfter(end))
                .sorted((a, b) -> b.exceptionDateTime().compareTo(a.exceptionDateTime()))
                .toList();
    }

    /** 테스트용: 실패 내역을 특정 시각으로 직접 넣는다 (오래된 watermark 시나리오) */
    public synchronized void addFailed(FailedRecord r) {
        failed.add(r);
    }

    public long sentCount() {
        return sent.get();
    }

    public long droppedCount() {
        return dropped.get();
    }
}
