package io.github.gumwoo.fulfillment.reconciler.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 주기 실행 (설계 4.4). 시나리오 테스트는 끄고 작업을 직접 호출한다. 실패하면 watermark가 그대로라 다음 주기에 같은 구간부터 다시 한다. */
@Component
@ConditionalOnProperty(name = "app.reconciler.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class ReconcilerScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconcilerScheduler.class);

    private final StatusChangeRequeryJob requeryJob;
    private final FailedWebhookRecoveryJob failedWebhookJob;
    private final ReconciliationJob reconciliationJob;

    public ReconcilerScheduler(StatusChangeRequeryJob requeryJob, FailedWebhookRecoveryJob failedWebhookJob,
                               ReconciliationJob reconciliationJob) {
        this.requeryJob = requeryJob;
        this.failedWebhookJob = failedWebhookJob;
        this.reconciliationJob = reconciliationJob;
    }

    @Scheduled(fixedDelayString = "${app.reconciler.requery-interval:PT5M}")
    public void requery() {
        try {
            requeryJob.run();
        } catch (RuntimeException e) {
            log.warn("STATUS_CHANGE 재조회 실패: {}", e.toString());
        }
    }

    @Scheduled(fixedDelayString = "${app.reconciler.failed-webhook-interval:PT10M}")
    public void failedWebhooks() {
        try {
            failedWebhookJob.run();
        } catch (RuntimeException e) {
            log.warn("실패 웹훅 복구 실패: {}", e.toString());
        }
    }

    @Scheduled(cron = "${app.reconciler.reconcile-cron:0 30 4 * * *}", zone = "Asia/Seoul")
    public void reconcile() {
        try {
            ReconciliationJob.Result r = reconciliationJob.run();
            log.info("대사 완료 run={} 비교 {} 불일치 {}", r.runId(), r.compared(), r.mismatches());
        } catch (RuntimeException e) {
            log.warn("대사 실패: {}", e.toString());
        }
    }
}
