package io.github.gumwoo.fulfillment.inbound.webhook;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 샵바이 웹훅 수신. 웹훅 method는 POST 또는 PUT (external-facts.md).
 * 원본을 저장한 뒤 바로 200을 준다. 비즈니스 처리는 worker가 큐에서 한다.
 */
@RestController
public class WebhookController {

    private final WebhookIngestService ingestService;

    public WebhookController(WebhookIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping(value = "/webhooks/shopby", consumes = "*/*")
    public ResponseEntity<Void> receivePost(@RequestBody String body) {
        ingestService.ingest(body);
        return ResponseEntity.ok().build();
    }

    @PutMapping(value = "/webhooks/shopby", consumes = "*/*")
    public ResponseEntity<Void> receivePut(@RequestBody String body) {
        return receivePost(body);
    }
}
