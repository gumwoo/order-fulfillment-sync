package io.github.gumwoo.fulfillment.reconciler.job;

import io.github.gumwoo.fulfillment.core.order.OrderStatusChange;
import io.github.gumwoo.fulfillment.core.port.ShopbyClient;
import io.github.gumwoo.fulfillment.core.port.ShopbyClient.OrderSearchPage;
import io.github.gumwoo.fulfillment.core.port.queue.MessageQueue;
import io.github.gumwoo.fulfillment.core.port.queue.MessageType;
import io.github.gumwoo.fulfillment.core.port.queue.QueueMessage;
import io.github.gumwoo.fulfillment.infra.json.OrderStatusChangeJson;
import io.github.gumwoo.fulfillment.infra.persistence.SyncWatermarkEntity;
import io.github.gumwoo.fulfillment.infra.persistence.SyncWatermarkRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 상태 변경 재조회 (설계 4.4): 마지막 성공 시각부터 지금까지 {@code searchDateType=STATUS_CHANGE}로 조회해
 * 주문옵션마다 ORDER_STATUS_CHANGED 메시지를 넣는다. worker가 웹훅과 같은 경로로 멱등 반영한다 (ADR-0001, ADR-0002).
 * 구간 경계에서 빠지는 건이 없도록 overlap만큼 겹쳐서 조회한다. 모든 페이지를 넣은 뒤에만 watermark를 옮긴다.
 */
@Component
public class StatusChangeRequeryJob {

    public static final String SOURCE = "POLLING";
    static final int PAGE_SIZE = 200;

    private static final Logger log = LoggerFactory.getLogger(StatusChangeRequeryJob.class);

    private final ShopbyClient shopby;
    private final MessageQueue queue;
    private final OrderStatusChangeJson json;
    private final SyncWatermarkRepository watermarks;
    private final Clock clock;
    private final Duration overlap;
    private final Duration initialLookback;

    public StatusChangeRequeryJob(ShopbyClient shopby, MessageQueue queue, OrderStatusChangeJson json,
                                  SyncWatermarkRepository watermarks, Clock clock,
                                  @Value("${app.reconciler.requery-overlap:PT2M}") Duration overlap,
                                  @Value("${app.reconciler.initial-lookback:PT1H}") Duration initialLookback) {
        this.shopby = shopby;
        this.queue = queue;
        this.json = json;
        this.watermarks = watermarks;
        this.clock = clock;
        this.overlap = overlap;
        this.initialLookback = initialLookback;
    }

    /** @return 큐에 넣은 주문옵션 수 */
    public int run() {
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        LocalDateTime from = watermarks.findById(SyncWatermarkEntity.POLLING)
                .map(SyncWatermarkEntity::getLastSuccessAt)
                .orElse(now.minus(initialLookback))
                .minus(overlap);
        int enqueued = requery(from, now, SOURCE);
        SyncWatermarkEntity wm = watermarks.findById(SyncWatermarkEntity.POLLING)
                .orElse(new SyncWatermarkEntity(SyncWatermarkEntity.POLLING, now));
        wm.advanceTo(now);
        watermarks.save(wm);
        return enqueued;
    }

    /**
     * 구간을 모두 조회해 큐에 넣는다. 실패 웹훅 복구도 이 메서드로 구간 재조회를 한다.
     *
     * <p>오프셋 페이징(주문번호 내림차순)이라, 조회 중 앞쪽 주문이 다시 바뀌어 구간 밖으로 나가면 뒤 페이지가 당겨져
     * 한 주문을 건너뛸 수 있다. 페이지마다 totalCount를 비교해 바뀌었으면 처음부터 다시 조회한다.
     * 이미 넣은 메시지는 멱등 반영되므로 다시 넣어도 결과는 같다. 계속 바뀌면 예외를 던져 watermark를 옮기지 않는다.
     */
    public int requery(LocalDateTime from, LocalDateTime to, String source) {
        for (int attempt = 1; attempt <= MAX_PASSES; attempt++) {
            Integer enqueued = requeryOnce(from, to, source);
            if (enqueued != null) {
                log.info("재조회 {} ~ {} source={} 주문옵션 {}건 큐 투입", from, to, source, enqueued);
                return enqueued;
            }
            log.info("재조회 중 결과 수가 바뀌어 처음부터 다시 ({}회째)", attempt);
        }
        throw new IllegalStateException("재조회 결과가 계속 바뀌어 구간을 확정하지 못함: " + from + " ~ " + to);
    }

    static final int MAX_PASSES = 3;

    /** @return 넣은 건수, 조회 중 totalCount가 바뀌었으면 null */
    private Integer requeryOnce(LocalDateTime from, LocalDateTime to, String source) {
        int enqueued = 0;
        int page = 1;
        Integer firstTotal = null;
        while (true) {
            OrderSearchPage result = shopby.searchOrdersByStatusChange(from, to, page, PAGE_SIZE);
            if (firstTotal == null) {
                firstTotal = result.totalCount();
            } else if (firstTotal != result.totalCount()) {
                return null;
            }
            for (OrderStatusChange change : result.options()) {
                queue.send(QueueMessage.of(MessageType.ORDER_STATUS_CHANGED, json.toElementJson(change), source));
                enqueued++;
            }
            if (result.orderCount() < PAGE_SIZE || (long) page * PAGE_SIZE >= result.totalCount()) {
                return enqueued;
            }
            page++;
        }
    }
}
