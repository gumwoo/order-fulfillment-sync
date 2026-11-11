package io.github.gumwoo.fulfillment.worker.job;

import io.github.gumwoo.fulfillment.core.cancel.CancelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 배송보류 대기 건 일괄 처리와 중간 단계 건 이어 하기 (ADR-0005, ADR-0009). 취소 건은 행 잠금(FOR UPDATE, SKIP LOCKED)으로
 * 진행하므로 여러 인스턴스가 동시에 돌아도 한 건을 두 곳에서 진행하지 않는다. 시나리오 테스트는 끄고 직접 호출한다.
 */
@Component
@ConditionalOnProperty(name = "app.worker.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class CancelScheduler {

    private static final Logger log = LoggerFactory.getLogger(CancelScheduler.class);

    private final CancelService cancelService;

    public CancelScheduler(CancelService cancelService) {
        this.cancelService = cancelService;
    }

    @Scheduled(fixedDelayString = "${app.worker.hold-interval:PT5S}")
    public void run() {
        try {
            CancelService.HoldResult r = cancelService.holdPending();
            if (r.pending() > 0) {
                log.info("배송보류 일괄 처리: 대기 {}, 보류 {}, 실패 {}, 일괄 실패 여부 {}", r.pending(), r.held(), r.failed(), r.bulkFailed());
            }
            cancelService.resume();
        } catch (RuntimeException e) {
            log.warn("배송보류 처리 실패, 다음 주기에 다시: {}", e.toString());
        }
    }
}
