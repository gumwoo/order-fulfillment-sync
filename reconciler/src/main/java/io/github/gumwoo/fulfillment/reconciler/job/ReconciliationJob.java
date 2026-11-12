package io.github.gumwoo.fulfillment.reconciler.job;

import io.github.gumwoo.fulfillment.core.order.OrderStatusChange;
import io.github.gumwoo.fulfillment.core.port.ShopbyClient;
import io.github.gumwoo.fulfillment.core.port.ShopbyClient.OrderSearchPage;
import io.github.gumwoo.fulfillment.infra.persistence.MismatchEntity;
import io.github.gumwoo.fulfillment.infra.persistence.MismatchRepository;
import io.github.gumwoo.fulfillment.infra.persistence.OrderLineStateEntity;
import io.github.gumwoo.fulfillment.infra.persistence.OrderLineStateRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 대사 배치 (설계 4.4): 샵바이 조회 결과(최종 기준, ADR-0002)와 우리 DB를 비교해 차이를 mismatch로 기록한다.
 * 대상은 lookback 구간 안에 상태가 바뀐 주문옵션이다(STATUS_CHANGE). 3PL 비교는 3PL 연동 기능에서 붙인다.
 */
@Component
public class ReconciliationJob {

    public static final String MISSING_LINE = "MISSING_LINE";
    public static final String STATUS_MISMATCH = "STATUS_MISMATCH";
    public static final String CLAIM_MISMATCH = "CLAIM_MISMATCH";

    private final ShopbyClient shopby;
    private final OrderLineStateRepository lines;
    private final MismatchRepository mismatches;
    private final io.github.gumwoo.fulfillment.infra.persistence.ReconciliationRunRepository runs;
    private final Clock clock;
    private final Duration lookback;

    public ReconciliationJob(ShopbyClient shopby, OrderLineStateRepository lines, MismatchRepository mismatches,
                             io.github.gumwoo.fulfillment.infra.persistence.ReconciliationRunRepository runs,
                             Clock clock, @Value("${app.reconciler.reconcile-lookback:P2D}") Duration lookback) {
        this.shopby = shopby;
        this.lines = lines;
        this.mismatches = mismatches;
        this.runs = runs;
        this.clock = clock;
        this.lookback = lookback;
    }

    public record Result(String runId, int compared, int mismatches) {
    }

    public Result run() {
        String runId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        LocalDateTime from = now.minus(lookback);
        List<MismatchEntity> found = new ArrayList<>();
        int compared = 0;
        int page = 1;
        while (true) {
            OrderSearchPage p = shopby.searchOrdersByStatusChange(from, now, page, StatusChangeRequeryJob.PAGE_SIZE);
            for (OrderStatusChange theirs : p.options()) {
                compared++;
                OrderLineStateEntity ours = lines.findById(theirs.orderProductOptionNo()).orElse(null);
                MismatchEntity m = compare(runId, theirs, ours, now);
                if (m != null) {
                    found.add(m);
                }
            }
            if (p.orderCount() < StatusChangeRequeryJob.PAGE_SIZE
                    || (long) page * StatusChangeRequeryJob.PAGE_SIZE >= p.totalCount()) {
                break;
            }
            page++;
        }
        mismatches.saveAll(found);
        runs.save(new io.github.gumwoo.fulfillment.infra.persistence.ReconciliationRunEntity(runId, now, compared, found.size()));
        return new Result(runId, compared, found.size());
    }

    private static MismatchEntity compare(String runId, OrderStatusChange theirs, OrderLineStateEntity ours,
                                          LocalDateTime now) {
        long no = theirs.orderProductOptionNo();
        if (ours == null) {
            return new MismatchEntity(runId, no, MISSING_LINE, theirs.orderStatus().name(), null, now);
        }
        var state = ours.toDomain();
        if (state.orderStatus() != theirs.orderStatus()) {
            return new MismatchEntity(runId, no, STATUS_MISMATCH, theirs.orderStatus().name(), state.orderStatus().name(), now);
        }
        if (!Objects.equals(blankToNull(theirs.claimStatusType()), blankToNull(state.claimStatusType()))) {
            return new MismatchEntity(runId, no, CLAIM_MISMATCH, theirs.claimStatusType(), state.claimStatusType(), now);
        }
        return null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
