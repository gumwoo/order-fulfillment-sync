package io.github.gumwoo.fulfillment.inbound.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gumwoo.fulfillment.core.port.queue.MessageType;
import io.github.gumwoo.fulfillment.infra.persistence.OutboxEntity;
import io.github.gumwoo.fulfillment.infra.persistence.OutboxRepository;
import io.github.gumwoo.fulfillment.infra.persistence.WebhookInboxEntity;
import io.github.gumwoo.fulfillment.infra.persistence.WebhookInboxRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 원본을 webhook_inbox에 저장하고, 같은 트랜잭션에서 CHANGE_ORDER_STATUS 배열 원소마다 outbox 행을 쓴다.
 * 파싱에 실패해도 원본은 남기고 200을 준다 (4xx를 주면 샵바이는 재전송하지 않는다).
 */
@Service
public class WebhookIngestService {

    static final String CHANGE_ORDER_STATUS = "CHANGE_ORDER_STATUS";
    static final String SOURCE_WEBHOOK = "WEBHOOK";

    private static final Logger log = LoggerFactory.getLogger(WebhookIngestService.class);

    private final WebhookInboxRepository inboxRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WebhookIngestService(WebhookInboxRepository inboxRepository, OutboxRepository outboxRepository,
                                ObjectMapper objectMapper, Clock clock) {
        this.inboxRepository = inboxRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public void ingest(String rawJson) {
        LocalDateTime now = LocalDateTime.now(clock);
        JsonNode root;
        try {
            root = objectMapper.readTree(rawJson);
        } catch (JsonProcessingException e) {
            log.warn("웹훅 본문 파싱 실패, 원본만 저장: {}", e.getOriginalMessage());
            inboxRepository.save(new WebhookInboxEntity(now, null, rawJson, "INVALID"));
            return;
        }
        // 가이드 샘플은 배열이지만, 원소마다 이벤트 종류를 확인한다 (단일 객체도 원소 하나로 본다)
        List<JsonNode> elements = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(elements::add);
        } else {
            elements.add(root);
        }
        List<JsonNode> orderChanges = elements.stream()
                .filter(e -> e.isObject() && CHANGE_ORDER_STATUS.equals(e.path("eventType").asText(null))
                        && e.hasNonNull("orderProductOptionNo"))
                .toList();
        String eventType = elements.stream().filter(JsonNode::isObject)
                .map(e -> e.path("eventType").asText(null)).filter(t -> t != null).findFirst().orElse(null);
        if (orderChanges.isEmpty()) {
            inboxRepository.save(new WebhookInboxEntity(now, eventType, rawJson, "IGNORED"));
            return;
        }
        if (orderChanges.size() != elements.size()) {
            log.warn("웹훅 배열 원소 {}개 중 {}개만 주문상태변경이라 나머지는 건너뛴다", elements.size(), orderChanges.size());
        }
        WebhookInboxEntity inbox = inboxRepository.save(
                new WebhookInboxEntity(now, CHANGE_ORDER_STATUS, rawJson, "QUEUED"));
        List<OutboxEntity> rows = new ArrayList<>();
        for (JsonNode element : orderChanges) {
            rows.add(new OutboxEntity(inbox.getId(), MessageType.ORDER_STATUS_CHANGED, SOURCE_WEBHOOK,
                    element.toString(), now));
        }
        outboxRepository.saveAll(rows);
    }
}
