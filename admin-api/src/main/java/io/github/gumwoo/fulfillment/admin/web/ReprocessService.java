package io.github.gumwoo.fulfillment.admin.web;

import io.github.gumwoo.fulfillment.core.order.OrderLineState;
import io.github.gumwoo.fulfillment.core.port.OrderSyncStore;
import io.github.gumwoo.fulfillment.core.shipment.ShipmentRequestService;
import io.github.gumwoo.fulfillment.infra.persistence.AdminActionEntity;
import io.github.gumwoo.fulfillment.infra.persistence.AdminActionRepository;
import io.github.gumwoo.fulfillment.infra.persistence.UnlinkedEntity;
import io.github.gumwoo.fulfillment.infra.persistence.UnlinkedRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 미연동 건 재처리 (설계 4.9).
 * <ol>
 *   <li>요청 ID(Idempotency-Key)를 admin_action에 INSERT IGNORE. 이미 있으면 처리하지 않고 기존 결과를 돌려준다
 *       (같은 버튼을 두 번 눌러도, 동시에 눌러도 한 번). 실패한 요청도 결과만 돌려주므로 다시 시도하려면 새 요청 ID를 쓴다.</li>
 *   <li>짧은 트랜잭션: 미연동 행을 잠그고, 이미 해결됐거나 다른 요청이 처리 중이면 멈춘다. 아니면 처리 중 표시를 남기고 커밋한다.
 *       처리 중 표시가 {@link #STALE_AFTER}보다 오래되면 끊긴 처리로 보고 다시 선점할 수 있다.</li>
 *   <li>잠금 없이 출고 요청(3PL 등록 → 상품준비중)을 한다. 단계마다 커밋되므로 중간 실패에도 3PL 등록 기록은 남는다.</li>
 *   <li>짧은 트랜잭션: 결과에 따라 해결 처리하고 처리 중 표시를 지운다. 이력에 결과를 남긴다.</li>
 * </ol>
 * 주문이 취소돼 연동할 필요가 없어진 건은 "취소로 종료"로 닫는다.
 */
@Service
public class ReprocessService {

    public static final String ACTION = "REPROCESS_UNLINKED";
    static final Duration STALE_AFTER = Duration.ofMinutes(5);

    public record Outcome(boolean duplicateRequest, String status, String result, String detail) {
    }

    /** 같은 요청 ID를 다른 대상에 다시 쓴 경우 */
    public static class RequestIdReusedException extends RuntimeException {
        public RequestIdReusedException(String message) {
            super(message);
        }
    }

    private final AdminActionRepository actions;
    private final UnlinkedRepository unlinked;
    private final ShipmentRequestService shipments;
    private final OrderSyncStore orders;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ReprocessService(AdminActionRepository actions, UnlinkedRepository unlinked, ShipmentRequestService shipments,
                            OrderSyncStore orders, TransactionTemplate tx, Clock clock) {
        this.actions = actions;
        this.unlinked = unlinked;
        this.shipments = shipments;
        this.orders = orders;
        this.tx = tx;
        this.clock = clock;
    }

    public Outcome reprocessUnlinked(long unlinkedId, String requestId, String actor) {
        String target = "unlinked:" + unlinkedId;
        Integer inserted = tx.execute(s -> actions.insertIgnore(requestId, actor, ACTION, target, now()));
        if (inserted == null || inserted == 0) {
            AdminActionEntity existing = actions.findByRequestId(requestId).orElseThrow();
            if (!existing.getTargetKey().equals(target)) {
                throw new RequestIdReusedException("요청 ID " + requestId + "는 " + existing.getTargetKey() + "에 이미 쓰였다");
            }
            return new Outcome(true, existing.getStatus(), existing.getResult(), existing.getDetail());
        }
        String status = AdminActionEntity.DONE;
        String result;
        String detail;
        boolean claimed = false;
        try {
            String[] claim = tx.execute(s -> claim(unlinkedId, requestId));
            result = claim[0];
            detail = claim[1];
            if (result == null) {
                claimed = true;
                String[] done = process(unlinkedId, requestId, actor);
                result = done[0];
                detail = done[1];
            }
        } catch (RuntimeException e) {
            status = AdminActionEntity.FAILED;
            result = "RETRY_LATER";
            detail = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (claimed) {
                tx.executeWithoutResult(s -> unlinked.lockById(unlinkedId)
                        .ifPresent(u -> u.endReprocess(requestId)));
            }
        }
        String fs = status;
        String fr = result;
        String fd = detail;
        tx.executeWithoutResult(s -> actions.findByRequestId(requestId).orElseThrow().finish(fs, fr, fd, now()));
        return new Outcome(false, status, result, detail);
    }

    /** @return 처리하지 않을 이유([결과, 상세]) 또는 선점했으면 [null, null] */
    private String[] claim(long unlinkedId, String requestId) {
        UnlinkedEntity u = unlinked.lockById(unlinkedId).orElse(null);
        if (u == null) {
            return new String[] {"NOT_FOUND", null};
        }
        if (u.getResolvedAt() != null) {
            return new String[] {"ALREADY_RESOLVED", "이미 " + u.getResolver() + "가 해결"};
        }
        if (u.isReprocessing(now().minus(STALE_AFTER))) {
            return new String[] {"IN_PROGRESS_BY_OTHER", "요청 " + u.getReprocessRequestId() + "가 처리 중"};
        }
        u.startReprocess(requestId, now());
        return new String[] {null, null};
    }

    /** 잠금 없이 출고 요청을 하고, 짧은 트랜잭션으로 결과를 반영한다 */
    private String[] process(long unlinkedId, String requestId, String actor) {
        long optionNo = tx.execute(s -> unlinked.findById(unlinkedId).orElseThrow().getOrderProductOptionNo());
        OrderLineState line = tx.execute(s -> orders.findLine(optionNo).orElse(null));
        ShipmentRequestService.Result res = line != null && line.hasClaim() ? null : shipments.ensureRequested(optionNo);
        return tx.execute(s -> {
            UnlinkedEntity u = unlinked.lockById(unlinkedId).orElseThrow();
            u.endReprocess(requestId);
            if (line != null && line.hasClaim()) {
                u.resolve(actor, now());
                return new String[] {"CLOSED_CANCELED", "주문에 클레임(" + line.claimStatusType() + ")이 있어 연동하지 않고 종료"};
            }
            if (res == ShipmentRequestService.Result.REQUESTED) {
                u.resolve(actor, now());
                return new String[] {"RESOLVED", "3PL 등록·상품준비중 완료"};
            }
            if (res == ShipmentRequestService.Result.UNLINKED) {
                return new String[] {"STILL_UNLINKED", "3PL이 다시 거절: " + u.getReasonCode()};
            }
            return new String[] {"SKIPPED", line == null ? "주문 라인 없음" : "주문 상태 " + line.orderStatus() + "라 출고 요청 대상 아님"};
        });
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
