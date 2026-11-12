package io.github.gumwoo.fulfillment.core.cancel;

import io.github.gumwoo.fulfillment.core.order.ClaimStatus;
import io.github.gumwoo.fulfillment.core.order.OrderLineState;
import io.github.gumwoo.fulfillment.core.port.CancelStore;
import io.github.gumwoo.fulfillment.core.port.CancelStore.CancelCase;
import io.github.gumwoo.fulfillment.core.port.ConflictRecorder;
import io.github.gumwoo.fulfillment.core.port.OrderSyncStore;
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
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * 취소·출고 경합 처리 (ADR-0005, 세부 규칙은 ADR-0009). 취소 신청이 오면 3PL 상태를 먼저 본다.
 * <ul>
 *   <li>3PL에 없음·출고요청: 3PL 취소(무료) → 샵바이 클레임 승인 (F-08)</li>
 *   <li>출고작업(피킹 전): 배송보류 대기 → {@link #holdPending()} 일괄 배송보류(하나라도 실패하면 전부 실패 → 건별 재시도)
 *       → {@link #resume()}가 3PL 취소(수수료) → 클레임 승인 (F-09)</li>
 *   <li>피킹·출고완료: 취소 불가 → CS 회수 건 기록 → 샵바이 이미출고 (F-10)</li>
 * </ul>
 * 단계마다 행 잠금 안에서 "현재 단계 확인 → 외부 호출 1회 → 다음 단계 저장"을 한다. 같은 건을 메시지 처리와 주기 작업이
 * 동시에 진행해도 한쪽만 진행하고, 다른 쪽은 잠금을 기다린 뒤 바뀐 단계를 본다.
 * 외부 호출이 성공했는데 저장 전에 죽으면 다음 시도가 같은 호출을 다시 한다. 승인·이미출고가 400으로 거절되면
 * 이미 처리됐을 수 있으므로 conflict로 남기고 다음 단계로 간다 (무한 재시도 대신 사람이 확인).
 */
public class CancelService {

    public static final int HOLD_BATCH_LIMIT = 1000;
    public static final String CONFLICT_HOLD_FAILED = "HOLD_DELIVERY_FAILED";
    public static final String CONFLICT_APPROVE_REJECTED = "CLAIM_APPROVE_REJECTED";
    public static final String CONFLICT_ALREADY_DELIVERY_REJECTED = "ALREADY_DELIVERY_REJECTED";
    static final String RECALL_REASON = "CANCEL_AFTER_PICKING";
    static final String HOLD_REASON = "취소 신청: 3PL 취소 진행 중";

    private static final Logger log = Logger.getLogger(CancelService.class.getName());

    private final CancelStore cases;
    private final OrderSyncStore orders;
    private final TplClient tpl;
    private final ShopbyClient shopby;
    private final ConflictRecorder conflicts;
    private final TransactionRunner tx;
    private final Clock clock;

    public CancelService(CancelStore cases, OrderSyncStore orders, TplClient tpl, ShopbyClient shopby,
                         ConflictRecorder conflicts, TransactionRunner tx, Clock clock) {
        this.cases = cases;
        this.orders = orders;
        this.tpl = tpl;
        this.shopby = shopby;
        this.conflicts = conflicts;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * 취소 신청 메시지 처리. 이미 열린 건이면 이어서 진행한다. 처음이면 우리 라인의 현재 클레임이 이 신청일 때만 연다
     * (순서가 뒤집혀 늦게 온 취소 신청, 이미 철회·완료된 클레임은 무시).
     */
    public CancelState onCancelRequested(long optionNo, long claimNo) {
        if (tx.inTransaction(() -> cases.find(optionNo, claimNo)).isEmpty()) {
            OrderLineState line = tx.inTransaction(() -> orders.findLine(optionNo).orElse(null));
            if (line == null || !ClaimStatus.CANCEL_REQUEST.equals(line.claimStatusType())
                    || !Objects.equals(line.claimNo(), claimNo)) {
                return CancelState.IGNORED;
            }
            tx.inTransaction(() -> {
                cases.createIfAbsent(optionNo, claimNo, now());
                return null;
            });
        }
        return advance(optionNo, claimNo);
    }

    /** 대기·종료 단계에 닿을 때까지 한 단계씩 진행한다 */
    CancelState advance(long optionNo, long claimNo) {
        CancelState state = null;
        for (int i = 0; i < 6; i++) {
            state = tx.inTransaction(() -> step(optionNo, claimNo));
            if (state.isWaitingOrTerminal()) {
                return state;
            }
        }
        return state;
    }

    /** 잠금 안에서 한 단계. 외부 호출이 예외를 던지면 트랜잭션이 되돌아가 단계는 그대로다. */
    private CancelState step(long optionNo, long claimNo) {
        CancelCase c = cases.lock(optionNo, claimNo).orElse(null);
        if (c == null) {
            return CancelState.IGNORED;
        }
        switch (c.state()) {
            case RECEIVED -> {
                TplOrder order = tpl.find(optionNo).orElse(null);
                Status s = order == null ? null : order.status();
                if (s == Status.WORKING) {
                    return save(c, CancelState.HOLD_PENDING, s.name(), null, "출고작업 중: 배송보류 대기");
                }
                if (s == Status.PICKED || s == Status.RELEASED) {
                    return startRecall(c, order, s.name());
                }
                return cancelAtTpl(c, s);
            }
            case HELD -> {
                return cancelAtTpl(c, Status.WORKING);
            }
            case TPL_CANCELED -> {
                try {
                    shopby.approveClaim(claimNo);
                } catch (ShopbyClient.ApiException e) {
                    if (e.status() != 400) {
                        throw e;
                    }
                    conflicts.record(String.valueOf(optionNo), CONFLICT_APPROVE_REJECTED,
                            "claimNo=" + claimNo + " 승인 거절(이미 처리됐을 수 있음): " + e.getMessage(), now());
                }
                return save(c, CancelState.DONE, null, null, "샵바이 취소 승인");
            }
            case RECALL_PENDING -> {
                TplOrder order = tpl.find(optionNo).orElse(null);
                String company = order == null ? null : order.deliveryCompanyType();
                String invoice = order == null ? null : order.invoiceNo();
                try {
                    shopby.alreadyDelivery(claimNo, company, invoice, LocalDate.now(clock));
                    // 샵바이는 클레임을 철회하고 배송중으로 바꿨다. 이미 배송중이던 주문은 단계 시각이 그대로라
                    // 그 변경이 웹훅·재조회로 와도 "오래된 변경"으로 버려지므로, 우리가 한 호출의 결과를 직접 반영한다 (ADR-0012)
                    orders.applyAlreadyDelivery(optionNo, claimNo, company, invoice, now());
                } catch (ShopbyClient.ApiException e) {
                    if (e.status() != 400) {
                        throw e;
                    }
                    conflicts.record(String.valueOf(optionNo), CONFLICT_ALREADY_DELIVERY_REJECTED,
                            "claimNo=" + claimNo + " 이미출고 거절(이미 처리됐을 수 있음): " + e.getMessage(), now());
                }
                return save(c, CancelState.RECALL_REQUIRED, null, null, "이미출고 처리, 회수 안내 필요");
            }
            default -> {
                return c.state();
            }
        }
    }

    /** 3PL 취소. 거절됐는데 이미 취소 상태면 취소 완료로 본다. 피킹 이후로 넘어갔으면 회수 경로. */
    private CancelState cancelAtTpl(CancelCase c, Status statusAtRequest) {
        if (statusAtRequest == null) {
            return save(c, CancelState.TPL_CANCELED, null, 0, "3PL 미등록");
        }
        CancelResult r = tpl.cancel(c.orderProductOptionNo());
        if (r.canceled() || r.status() == Status.CANCELED) {
            return save(c, CancelState.TPL_CANCELED, statusAtRequest.name(), r.fee(), "3PL 취소, 수수료 " + r.fee());
        }
        TplOrder order = tpl.find(c.orderProductOptionNo()).orElse(null);
        return startRecall(c, order, r.status() == null ? "UNKNOWN" : r.status().name());
    }

    /** 회수 건을 먼저 기록하고(이미출고 호출 뒤 저장 전에 죽어도 회수 건은 남는다) RECALL_PENDING으로 */
    private CancelState startRecall(CancelCase c, TplOrder order, String tplStatus) {
        cases.addRecallTask(c.orderProductOptionNo(), c.claimNo(), order == null ? null : order.invoiceNo(), RECALL_REASON,
                now());
        return save(c, CancelState.RECALL_PENDING, tplStatus, null, "피킹 이후 취소: 이미출고 처리 대기");
    }

    /**
     * 배송보류 대기 건을 일괄 처리한다. 하나라도 실패하면 전부 미반영(external-facts.md)이므로, 일괄이 거절되면 건별로 다시 한다.
     * 건별 400이면 3PL 상태를 다시 봐서 피킹 이후면 회수 경로로, 아니면 HOLD_FAILED(conflict). 5xx는 다음 주기에 다시.
     * 보류된 건의 3PL 취소와 승인은 {@link #resume()}가 이어서 한다.
     */
    public HoldResult holdPending() {
        return tx.inTransaction(() -> {
            List<CancelCase> pending = cases.lockByState(CancelState.HOLD_PENDING, HOLD_BATCH_LIMIT);
            if (pending.isEmpty()) {
                return new HoldResult(0, 0, 0, false);
            }
            List<CancelCase> held = new ArrayList<>();
            int failed = 0;
            boolean bulkFailed = false;
            try {
                shopby.holdDelivery(pending.stream().map(CancelCase::orderProductOptionNo).toList(), HOLD_REASON);
                held.addAll(pending);
            } catch (ShopbyClient.ApiException bulk) {
                bulkFailed = true;
                for (CancelCase c : pending) {
                    try {
                        shopby.holdDelivery(List.of(c.orderProductOptionNo()), HOLD_REASON);
                        held.add(c);
                    } catch (ShopbyClient.ApiException single) {
                        if (single.status() >= 500) {
                            continue;
                        }
                        Optional<TplOrder> order = tpl.find(c.orderProductOptionNo());
                        Status s = order.map(TplOrder::status).orElse(null);
                        if (s == Status.PICKED || s == Status.RELEASED) {
                            startRecall(c, order.get(), s.name());
                        } else {
                            failed++;
                            save(c, CancelState.HOLD_FAILED, null, null, "배송보류 거절: " + single.getMessage());
                            conflicts.record(String.valueOf(c.orderProductOptionNo()), CONFLICT_HOLD_FAILED,
                                    "claimNo=" + c.claimNo() + " " + single.getMessage(), now());
                        }
                    }
                }
            }
            for (CancelCase c : held) {
                save(c, CancelState.HELD, null, null, "배송보류 완료");
            }
            return new HoldResult(pending.size(), held.size(), failed, bulkFailed);
        });
    }

    public record HoldResult(int pending, int held, int failed, boolean bulkFailed) {
    }

    /**
     * 중간 단계(HELD, TPL_CANCELED, RECALL_PENDING)의 건을 이어서 진행한다. 주기 작업이 holdPending() 다음에 호출한다.
     * 한 건의 실패가 다른 건을 막지 않게 하고, 실패는 로그로 남긴다(단계가 그대로라 다음 주기에 다시 한다).
     *
     * @return 진행을 시도한 건수
     */
    public int resume() {
        int n = 0;
        for (CancelState s : List.of(CancelState.HELD, CancelState.TPL_CANCELED, CancelState.RECALL_PENDING)) {
            for (CancelCase c : tx.inTransaction(() -> cases.findByState(s, HOLD_BATCH_LIMIT))) {
                n++;
                try {
                    advance(c.orderProductOptionNo(), c.claimNo());
                } catch (RuntimeException e) {
                    log.warning("취소 건 진행 실패 option=" + c.orderProductOptionNo() + " state=" + s + ": " + e);
                }
            }
        }
        return n;
    }

    private CancelState save(CancelCase c, CancelState state, String tplStatus, Integer fee, String detail) {
        cases.update(c.orderProductOptionNo(), c.claimNo(), state, tplStatus, fee, detail, now());
        return state;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
