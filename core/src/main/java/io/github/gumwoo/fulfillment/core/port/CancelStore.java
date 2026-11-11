package io.github.gumwoo.fulfillment.core.port;

import io.github.gumwoo.fulfillment.core.cancel.CancelState;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 취소 건(주문옵션 + 클레임번호)의 진행 상태 영속성 포트 (ADR-0005, ADR-0009).
 * lock 계열 메서드는 행 잠금을 잡으므로 호출자의 트랜잭션 안에서만 쓴다.
 */
public interface CancelStore {

    record CancelCase(long orderProductOptionNo, long claimNo, CancelState state, String tplStatusAtRequest, int fee,
                      String detail) {
    }

    Optional<CancelCase> find(long orderProductOptionNo, long claimNo);

    /** 없으면 RECEIVED로 만든다 (동시에 와도 한 건, UNIQUE + INSERT IGNORE) */
    void createIfAbsent(long orderProductOptionNo, long claimNo, LocalDateTime at);

    /** 행 잠금 후 읽는다 (SELECT ... FOR UPDATE). 같은 건을 동시에 진행하려는 쪽은 여기서 기다린다. */
    Optional<CancelCase> lock(long orderProductOptionNo, long claimNo);

    /** 해당 단계의 건을 잠그고 가져온다. 다른 트랜잭션이 잠근 건은 건너뛴다 (SKIP LOCKED). */
    List<CancelCase> lockByState(CancelState state, int limit);

    List<CancelCase> findByState(CancelState state, int limit);

    void update(long orderProductOptionNo, long claimNo, CancelState state, String tplStatus, Integer fee, String detail,
                LocalDateTime at);

    /** CS 회수 안내 목록 (피킹 이후 취소, F-10). 이미 있으면 그대로 둔다. */
    void addRecallTask(long orderProductOptionNo, long claimNo, String invoiceNo, String reason, LocalDateTime at);
}
