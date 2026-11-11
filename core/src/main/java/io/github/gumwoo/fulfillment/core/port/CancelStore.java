package io.github.gumwoo.fulfillment.core.port;

import io.github.gumwoo.fulfillment.core.cancel.CancelState;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 취소 건(주문옵션 + 클레임번호)의 진행 상태 영속성 포트 (ADR-0005). */
public interface CancelStore {

    record CancelCase(long orderProductOptionNo, long claimNo, CancelState state, String tplStatusAtRequest, int fee,
                      String detail) {
    }

    Optional<CancelCase> find(long orderProductOptionNo, long claimNo);

    /** 없으면 RECEIVED로 만든다. 이미 있으면 그대로 둔다. */
    CancelCase createIfAbsent(long orderProductOptionNo, long claimNo, LocalDateTime at);

    void update(long orderProductOptionNo, long claimNo, CancelState state, String tplStatus, Integer fee, String detail,
                LocalDateTime at);

    List<CancelCase> findByState(CancelState state, int limit);

    /** CS 회수 안내 목록 (피킹 이후 취소, F-10) */
    void addRecallTask(long orderProductOptionNo, long claimNo, String invoiceNo, String reason, LocalDateTime at);
}
