package io.github.gumwoo.fulfillment.reconciler.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 주기 실행 (설계 4.4). 시나리오 테스트는 끄고 작업을 직접 호출한다. */
@Component
@ConditionalOnProperty(name = "app.reconciler.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class ReconcilerScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconcilerScheduler.class);

    private final StatusChangeRequeryJob requeryJob;

    public ReconcilerScheduler(StatusChangeRequeryJob requeryJob) {
        this.requeryJob = requeryJob;
    }

    @Scheduled(fixedDelayString = "${app.reconciler.requery-interval:PT5M}")
    public void requery() {
        try {
            requeryJob.run();
        } catch (RuntimeException e) {
            log.warn("STATUS_CHANGE 재조회 실패, 다음 주기에 같은 구간부터 다시: {}", e.toString());
        }
    }
}
