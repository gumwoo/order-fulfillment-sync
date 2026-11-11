package io.github.gumwoo.fulfillment.worker.job;

import io.github.gumwoo.fulfillment.core.shipment.AlertBundler;
import io.github.gumwoo.fulfillment.core.shipment.InvoiceSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 3PL 이벤트 수집, 송장 반영, 묶음 알림 주기 실행. 시나리오 테스트는 끄고 직접 호출한다. */
@Component
@ConditionalOnProperty(name = "app.worker.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class ShipmentScheduler {

    private static final Logger log = LoggerFactory.getLogger(ShipmentScheduler.class);

    private final TplEventPollJob pollJob;
    private final InvoiceSyncService invoiceSync;
    private final AlertBundler alertBundler;

    public ShipmentScheduler(TplEventPollJob pollJob, InvoiceSyncService invoiceSync, AlertBundler alertBundler) {
        this.pollJob = pollJob;
        this.invoiceSync = invoiceSync;
        this.alertBundler = alertBundler;
    }

    @Scheduled(fixedDelayString = "${app.worker.tpl-poll-interval:PT1M}")
    public void pollTplEvents() {
        try {
            pollJob.poll();
        } catch (RuntimeException e) {
            log.warn("3PL 이벤트 수집 실패: {}", e.toString());
        }
    }

    @Scheduled(fixedDelayString = "${app.worker.invoice-sync-interval:PT1M}")
    public void syncInvoices() {
        try {
            InvoiceSyncService.Result r;
            do {
                r = invoiceSync.syncPending();
            } while (r.sent() == InvoiceSyncService.BATCH && r.failed() < r.sent());
        } catch (RuntimeException e) {
            log.warn("송장 반영 실패, 다음 주기에 다시: {}", e.toString());
        }
    }

    @Scheduled(fixedDelayString = "${app.worker.alert-bundle-interval:PT10M}")
    public void bundleAlerts() {
        try {
            alertBundler.flush();
        } catch (RuntimeException e) {
            log.warn("알림 묶음 실패: {}", e.toString());
        }
    }
}
