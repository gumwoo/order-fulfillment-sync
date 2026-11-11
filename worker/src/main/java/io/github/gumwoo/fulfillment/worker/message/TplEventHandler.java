package io.github.gumwoo.fulfillment.worker.message;

import io.github.gumwoo.fulfillment.core.port.TplClient.Event;
import io.github.gumwoo.fulfillment.core.port.queue.MessageHandler;
import io.github.gumwoo.fulfillment.core.port.queue.MessageHandler.Disposition;
import io.github.gumwoo.fulfillment.core.port.queue.QueueMessage;
import io.github.gumwoo.fulfillment.core.shipment.TplEventService;
import io.github.gumwoo.fulfillment.infra.json.TplEventJson;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * TPL_EVENT 처리. 출고완료가 송장보다 먼저 오면 바로 실패시키지 않고 지연 큐로 다시 넣어 N분 뒤 다시 본다 (설계 4.8, F-12).
 * 메시지별 지연은 SQS Standard 큐의 메시지 타이머를 쓴다 (FIFO는 지원하지 않음, ADR-0006).
 * 최대 횟수를 넘으면 conflict와 경보를 남긴다. 경보는 AlertBundler가 묶어 알림 한 건으로 만든다.
 */
@Component
public class TplEventHandler {

    private final TplEventJson json;
    private final TplEventService service;
    private final ProcessingStats stats;
    private final Duration releaseWait;
    private final int maxReleaseWaits;

    public TplEventHandler(TplEventJson json, TplEventService service, ProcessingStats stats,
                           @Value("${app.worker.release-wait:PT5M}") Duration releaseWait,
                           @Value("${app.worker.max-release-waits:6}") int maxReleaseWaits) {
        this.json = json;
        this.service = service;
        this.stats = stats;
        this.releaseWait = releaseWait;
        this.maxReleaseWaits = maxReleaseWaits;
    }

    public Disposition handle(QueueMessage message) {
        Event e = json.read(message.payload());
        TplEventService.Result r = service.apply(e);
        stats.record("TPL_EVENT", r.name());
        if (r != TplEventService.Result.WAIT_FOR_INVOICE) {
            return MessageHandler.done();
        }
        if (message.attempt() >= maxReleaseWaits) {
            service.giveUpRelease(e.orderProductOptionNo(), message.attempt());
            stats.record("TPL_EVENT", "GAVE_UP");
            return MessageHandler.done();
        }
        stats.record("TPL_EVENT", "DELAYED");
        return new MessageHandler.Republish(message.withNextAttempt(), releaseWait);
    }
}
