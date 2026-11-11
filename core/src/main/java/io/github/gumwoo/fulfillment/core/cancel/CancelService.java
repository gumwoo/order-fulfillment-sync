package io.github.gumwoo.fulfillment.core.cancel;

import io.github.gumwoo.fulfillment.core.port.CancelStore;
import io.github.gumwoo.fulfillment.core.port.CancelStore.CancelCase;
import io.github.gumwoo.fulfillment.core.port.ConflictRecorder;
import io.github.gumwoo.fulfillment.core.port.ShopbyClient;
import io.github.gumwoo.fulfillment.core.port.TplClient;
import io.github.gumwoo.fulfillment.core.port.TplClient.CancelResult;
import io.github.gumwoo.fulfillment.core.port.TplClient.Status;
import io.github.gumwoo.fulfillment.core.port.TplClient.TplOrder;
import io.github.gumwoo.fulfillment.core.port.TransactionRunner;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 취소·출고 경합 처리 (ADR-0005, 설계 4.6). 취소 신청이 오면 3PL 상태를 먼저 본다.
 * <ul>
 *   <li>3PL에 없음·출고요청: 3PL 취소(무료) → 샵바이 클레임 승인 (F-08)</li>
 *   <li>출고작업(피킹 전): 배송보류 대기 → {@link #holdPending()}가 일괄 배송보류(최대 1,000건, 하나라도 실패하면 전부 실패,
 *       실패하면 건별 재시도) → 3PL 취소(수수료) → 클레임 승인 (F-09)</li>
 *   <li>피킹·출고완료: 취소 불가 → 샵바이 이미출고 → CS 회수 목록 (F-10)</li>
 * </ul>
 * 상태를 단계마다 저장하므로 같은 취소 메시지를 여러 번 처리해도 지금 단계부터 이어서 한다.
 * 샵바이 RateLimitedException은 그대로 나가고 호출자가 재발행한다 (ADR-0007).
 */
public class CancelService {

    public static final int HOLD_BATCH_LIMIT = 1000;
    public static final String CONFLICT_HOLD_FAILED = "HOLD_DELIVERY_FAILED";

    private final CancelStore cases;
    private final TplClient tpl;
    private final ShopbyClient shopby;
    private final ConflictRecorder conflicts;
    private final TransactionRunner tx;
    private final Clock clock;

    public CancelService(CancelStore cases, TplClient tpl, ShopbyClient shopby, ConflictRecorder conflicts,
                         TransactionRunner tx, Clock clock) {
        this.cases = cases;
        this.tpl = tpl;
        this.shopby = shopby;
        this.conflicts = conflicts;
        this.tx = tx;
        this.clock = clock;
    }

    /** 취소 신청 메시지 처리. @return 이번 호출 후의 단계 */
    public CancelState onCancelRequested(long optionNo, long claimNo) {
        CancelCase c = tx.inTransaction(() -> cases.createIfAbsent(optionNo, claimNo, now()));
        return advance(c);
    }

    /** 단계별로 진행한다. 배송보류 대기(HOLD_PENDING)는 일괄 처리를 기다리므로 여기서 멈춘다. */
    CancelState advance(CancelCase c) {
        CancelState state = c.state();
        if (state == CancelState.RECEIVED) {
            Optional<TplOrder> order = tpl.find(c.orderProductOptionNo());
            Status s = order.map(TplOrder::status).orElse(null);
            if (s == null || s == Status.REQUESTED || s == Status.CANCELED) {
                state = cancelAtTpl(c, s);
            } else if (s == Status.WORKING) {
                save(c, CancelState.HOLD_PENDING, s.name(), null, "출고작업 중: 배송보류 대기");
                return CancelState.HOLD_PENDING;
            } else {
                return alreadyShipped(c, order.get(), s.name());
            }
        }
        if (state == CancelState.HELD) {
            state = cancelAtTpl(c, Status.WORKING);
        }
        if (state == CancelState.TPL_CANCELED) {
            shopby.approveClaim(c.claimNo());
            save(c, CancelState.DONE, null, null, "샵바이 취소 승인");
            return CancelState.DONE;
        }
        return state;
    }

    private CancelState cancelAtTpl(CancelCase c, Status statusAtRequest) {
        if (statusAtRequest == null) {
            // 3PL에 넘어가지 않은 주문: 3PL 쪽 할 일 없음
            save(c, CancelState.TPL_CANCELED, null, 0, "3PL 미등록");
            return CancelState.TPL_CANCELED;
        }
        CancelResult r = tpl.cancel(c.orderProductOptionNo());
        if (r.canceled()) {
            save(c, CancelState.TPL_CANCELED, statusAtRequest.name(), r.fee(), "3PL 취소, 수수료 " + r.fee());
            return CancelState.TPL_CANCELED;
        }
        // 그 사이 피킹이 시작됐다 (경합): 취소 불가 경로
        TplOrder order = tpl.find(c.orderProductOptionNo()).orElse(null);
        return alreadyShipped(c, order, r.status() == null ? "UNKNOWN" : r.status().name());
    }

    private CancelState alreadyShipped(CancelCase c, TplOrder order, String tplStatus) {
        String invoiceNo = order == null ? null : order.invoiceNo();
        String company = order == null ? null : order.deliveryCompanyType();
        shopby.alreadyDelivery(c.claimNo(), company, invoiceNo, LocalDate.now(clock));
        tx.inTransaction(() -> {
            cases.update(c.orderProductOptionNo(), c.claimNo(), CancelState.RECALL_REQUIRED, tplStatus, null,
                    "피킹 이후 취소: 이미출고 처리, 회수 안내 필요", now());
            cases.addRecallTask(c.orderProductOptionNo(), c.claimNo(), invoiceNo, "CANCEL_AFTER_PICKING", now());
            return null;
        });
        return CancelState.RECALL_REQUIRED;
    }

    /**
     * 배송보류 대기 건을 일괄 처리한다. 하나라도 실패하면 전부 미반영(external-facts.md)이므로,
     * 일괄이 실패하면 건별로 다시 시도한다. 성공한 건은 3PL 취소와 클레임 승인까지 이어서 진행한다.
     *
     * @return 이번에 처리한 결과
     */
    public HoldResult holdPending() {
        List<CancelCase> pending = tx.inTransaction(() -> cases.findByState(CancelState.HOLD_PENDING, HOLD_BATCH_LIMIT));
        if (pending.isEmpty()) {
            return new HoldResult(0, 0, 0, false);
        }
        List<CancelCase> held = new ArrayList<>();
        List<CancelCase> failed = new ArrayList<>();
        boolean bulkFailed = false;
        try {
            shopby.holdDelivery(pending.stream().map(CancelCase::orderProductOptionNo).toList(), "취소 신청: 3PL 취소 진행 중");
            held.addAll(pending);
        } catch (ShopbyClient.ApiException bulk) {
            bulkFailed = true;
            for (CancelCase c : pending) {
                try {
                    shopby.holdDelivery(List.of(c.orderProductOptionNo()), "취소 신청: 3PL 취소 진행 중");
                    held.add(c);
                } catch (ShopbyClient.ApiException single) {
                    failed.add(c);
                }
            }
        }
        for (CancelCase c : held) {
            save(c, CancelState.HELD, null, null, "배송보류 완료");
        }
        for (CancelCase c : failed) {
            save(c, CancelState.HOLD_FAILED, null, null, "배송보류 건별 재시도 실패");
            tx.inTransaction(() -> {
                conflicts.record(String.valueOf(c.orderProductOptionNo()), CONFLICT_HOLD_FAILED,
                        "claimNo=" + c.claimNo(), now());
                return null;
            });
        }
        for (CancelCase c : held) {
            advanceQuietly(new CancelCase(c.orderProductOptionNo(), c.claimNo(), CancelState.HELD,
                    c.tplStatusAtRequest(), c.fee(), c.detail()));
        }
        return new HoldResult(pending.size(), held.size(), failed.size(), bulkFailed);
    }

    /**
     * 중간 단계(HELD, TPL_CANCELED)에서 멈춘 건을 이어서 진행한다 (레이트 리밋·일시 장애로 끊긴 경우).
     * 주기 작업이 호출한다. @return 진행을 시도한 건수
     */
    public int resume() {
        int n = 0;
        for (CancelState s : List.of(CancelState.HELD, CancelState.TPL_CANCELED)) {
            for (CancelCase c : tx.inTransaction(() -> cases.findByState(s, HOLD_BATCH_LIMIT))) {
                advanceQuietly(c);
                n++;
            }
        }
        return n;
    }

    /** 한 건의 실패(레이트 리밋 등)가 같은 묶음의 다른 건을 막지 않게 한다. 실패한 건은 단계가 그대로라 resume()이 다시 한다. */
    private void advanceQuietly(CancelCase c) {
        try {
            advance(c);
        } catch (RuntimeException e) {
            // 단계가 저장되지 않았으므로 다음 resume()에서 같은 단계부터 다시 한다
        }
    }

    public record HoldResult(int pending, int held, int failed, boolean bulkFailed) {
    }

    private void save(CancelCase c, CancelState state, String tplStatus, Integer fee, String detail) {
        tx.inTransaction(() -> {
            cases.update(c.orderProductOptionNo(), c.claimNo(), state, tplStatus, fee, detail, now());
            return null;
        });
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
