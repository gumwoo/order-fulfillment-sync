package io.github.gumwoo.fulfillment.reconciler.job;

import io.github.gumwoo.fulfillment.core.port.ShopbyClient;
import io.github.gumwoo.fulfillment.core.port.ShopbyClient.FailedWebhook;
import io.github.gumwoo.fulfillment.core.port.ShopbyClient.FailedWebhookPage;
import io.github.gumwoo.fulfillment.infra.persistence.AlertEntity;
import io.github.gumwoo.fulfillment.infra.persistence.AlertRepository;
import io.github.gumwoo.fulfillment.infra.persistence.SyncWatermarkEntity;
import io.github.gumwoo.fulfillment.infra.persistence.SyncWatermarkRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 실패 웹훅 복구 (설계 4.4).
 * <ul>
 *   <li>watermark부터 지금까지 {@code GET /webhooks/failed}를 조회한다. 검색 기간은 최대 7일이다 (external-facts.md).</li>
 *   <li>실패 내역의 {@code data} 형식은 확인하지 못해 파싱하지 않는다. 대신 {@code exceptionDateTime} 앞뒤 구간을
 *       STATUS_CHANGE로 다시 조회해 큐에 넣는다. worker가 멱등 반영한다.</li>
 *   <li>watermark가 7일보다 오래되면 조회 자체가 불가능하므로 경보(STALE_WATERMARK)를 남기고,
 *       watermark부터 지금까지를 STATUS_CHANGE로 재조회해 복구한다 (F-06).</li>
 * </ul>
 */
@Component
public class FailedWebhookRecoveryJob {

    public static final String SOURCE = "FAILED_WEBHOOK";
    public static final String ALERT_STALE_WATERMARK = "STALE_WATERMARK";
    static final Duration SEARCH_LIMIT = Duration.ofDays(7);
    static final int PAGE_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(FailedWebhookRecoveryJob.class);

    private final ShopbyClient shopby;
    private final StatusChangeRequeryJob requery;
    private final SyncWatermarkRepository watermarks;
    private final AlertRepository alerts;
    private final Clock clock;
    private final Duration overlap;
    private final Duration initialLookback;
    private final Duration windowBefore;
    private final Duration windowAfter;

    public FailedWebhookRecoveryJob(ShopbyClient shopby, StatusChangeRequeryJob requery,
                                    SyncWatermarkRepository watermarks, AlertRepository alerts, Clock clock,
                                    @Value("${app.reconciler.failed-webhook-overlap:PT2M}") Duration overlap,
                                    @Value("${app.reconciler.initial-lookback:PT1H}") Duration initialLookback,
                                    @Value("${app.reconciler.failed-webhook-window-before:PT5M}") Duration windowBefore,
                                    @Value("${app.reconciler.failed-webhook-window-after:PT1M}") Duration windowAfter) {
        this.shopby = shopby;
        this.requery = requery;
        this.watermarks = watermarks;
        this.alerts = alerts;
        this.clock = clock;
        this.overlap = overlap;
        this.initialLookback = initialLookback;
        this.windowBefore = windowBefore;
        this.windowAfter = windowAfter;
    }

    public record Result(boolean staleWatermark, int failedWebhooks, int enqueued) {
    }

    public Result run() {
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        LocalDateTime watermark = watermarks.findById(SyncWatermarkEntity.FAILED_WEBHOOK)
                .map(SyncWatermarkEntity::getLastSuccessAt)
                .orElse(now.minus(initialLookback));
        LocalDateTime from = watermark.minus(overlap);
        Result result;
        if (Duration.between(from, now).compareTo(SEARCH_LIMIT) > 0) {
            // 재조회가 실패해 다음 주기에 다시 와도 같은 경보를 계속 쌓지 않는다 (1시간에 한 번)
            if (!alerts.existsByTypeAndTargetKeyAndCreatedAtAfter(ALERT_STALE_WATERMARK, SyncWatermarkEntity.FAILED_WEBHOOK,
                    now.minusHours(1))) {
                alerts.save(new AlertEntity(ALERT_STALE_WATERMARK, SyncWatermarkEntity.FAILED_WEBHOOK,
                        "실패 웹훅 watermark가 7일보다 오래됨(" + watermark + "). 실패 웹훅 조회 대신 STATUS_CHANGE 재조회로 복구",
                        now));
            }
            log.warn("실패 웹훅 watermark {} 가 7일 초과, STATUS_CHANGE 재조회로 복구", watermark);
            int enqueued = requery.requery(from, now, SOURCE);
            result = new Result(true, 0, enqueued);
        } else {
            List<FailedWebhook> failed = fetchAll(from, now);
            int enqueued = 0;
            for (Window w : windows(failed)) {
                enqueued += requery.requery(w.start, w.end, SOURCE);
            }
            result = new Result(false, failed.size(), enqueued);
        }
        SyncWatermarkEntity wm = watermarks.findById(SyncWatermarkEntity.FAILED_WEBHOOK)
                .orElse(new SyncWatermarkEntity(SyncWatermarkEntity.FAILED_WEBHOOK, now));
        wm.advanceTo(now);
        watermarks.save(wm);
        return result;
    }

    private List<FailedWebhook> fetchAll(LocalDateTime from, LocalDateTime to) {
        List<FailedWebhook> all = new ArrayList<>();
        int page = 1;
        while (true) {
            FailedWebhookPage p = shopby.searchFailedWebhooks(from, to, page, PAGE_SIZE);
            p.contents().stream().filter(f -> "CHANGE_ORDER_STATUS".equals(f.eventType())).forEach(all::add);
            if (p.contents().size() < PAGE_SIZE || (long) page * PAGE_SIZE >= p.totalCount()) {
                return all;
            }
            page++;
        }
    }

    private record Window(LocalDateTime start, LocalDateTime end) {
    }

    /**
     * 실패 시각마다 [시각 - windowBefore, 시각 + windowAfter] 구간을 만들고 겹치는 구간은 합친다.
     * 실패 시각과 상태 변경 시각의 관계는 확인하지 못했다(external-facts.md). 구간을 벗어난 건은
     * 주기적인 STATUS_CHANGE 재조회와 대사 배치가 잡는다.
     */
    private List<Window> windows(List<FailedWebhook> failed) {
        long noTime = failed.stream().filter(f -> f.exceptionDateTime() == null).count();
        if (noTime > 0) {
            log.warn("exceptionDateTime이 없는 실패 웹훅 {}건: 구간을 정할 수 없어 STATUS_CHANGE 재조회에 맡긴다", noTime);
        }
        List<FailedWebhook> sorted = failed.stream().filter(f -> f.exceptionDateTime() != null)
                .sorted(Comparator.comparing(FailedWebhook::exceptionDateTime)).toList();
        List<Window> merged = new ArrayList<>();
        for (FailedWebhook f : sorted) {
            LocalDateTime s = f.exceptionDateTime().minus(windowBefore);
            LocalDateTime e = f.exceptionDateTime().plus(windowAfter);
            if (!merged.isEmpty() && !s.isAfter(merged.get(merged.size() - 1).end)) {
                Window last = merged.remove(merged.size() - 1);
                merged.add(new Window(last.start, e.isAfter(last.end) ? e : last.end));
            } else {
                merged.add(new Window(s, e));
            }
        }
        return merged;
    }
}
