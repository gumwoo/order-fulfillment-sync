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
     * <p>오프셋 페이징(주문번호 내림차순)을 앞 페이지부터 읽으면, 조회 중 앞쪽 주문이 다시 바뀌어 구간 밖으로 나갈 때
     * 뒤 주문이 앞으로 당겨져 건너뛰게 된다. 우리 worker가 처리하며 상태를 바꾸므로 부하 중에 흔히 생긴다(F-07에서 재현).
     * <ul>
     *   <li>구간 끝은 과거(현재 초의 1초 전)로 둔다. 그러면 조회 중 구간에 새로 들어오는 주문은 없고, 나가는 주문만 있다.</li>
     *   <li>마지막 페이지부터 첫 페이지 순서로 읽는다. 아직 읽지 않은 앞쪽에서 주문이 나가면 당겨지는 것은 이미 읽은
     *       주문이라 중복만 생기고(멱등 반영), 이미 읽은 뒤쪽에서 나가는 것은 앞쪽 페이지에 영향이 없다.</li>
     *   <li>구간에서 나간 주문은 끝 시각 이후에 바뀐 것이라 다음 실행 구간에 들어간다.</li>
     * </ul>
     */
    public int requery(LocalDateTime from, LocalDateTime to, String source) {
        LocalDateTime safeEnd = LocalDateTime.now(clock).withNano(0).minusSeconds(1);
        LocalDateTime end = to.isAfter(safeEnd) ? safeEnd : to;
        if (end.isBefore(from)) {
            return 0;
        }
        OrderSearchPage first = shopby.searchOrdersByStatusChange(from, end, 1, PAGE_SIZE);
        int lastPage = Math.max(1, (first.totalCount() + PAGE_SIZE - 1) / PAGE_SIZE);
        int enqueued = 0;
        for (int page = lastPage; page >= 2; page--) {
            enqueued += enqueueAll(shopby.searchOrdersByStatusChange(from, end, page, PAGE_SIZE), source);
        }
        // 첫 페이지는 마지막에 다시 읽는다 (앞서 읽은 첫 페이지 이후 당겨진 주문까지 포함되게)
        OrderSearchPage firstAgain = lastPage == 1 ? first : shopby.searchOrdersByStatusChange(from, end, 1, PAGE_SIZE);
        enqueued += enqueueAll(firstAgain, source);
        log.info("재조회 {} ~ {} source={} 주문옵션 {}건 큐 투입", from, end, source, enqueued);
        return enqueued;
    }

    private int enqueueAll(OrderSearchPage page, String source) {
        for (OrderStatusChange change : page.options()) {
            queue.send(QueueMessage.of(MessageType.ORDER_STATUS_CHANGED, json.toElementJson(change), source));
        }
        return page.options().size();
    }
}
