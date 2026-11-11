package io.github.gumwoo.fulfillment.core.port;

import java.time.LocalDateTime;

/** 사람이 봐야 하는 충돌 기록 포트 (conflict 테이블) */
public interface ConflictRecorder {

    void record(String targetKey, String reasonCode, String detail, LocalDateTime at);

    /** 같은 대상·사유의 미해결 건이 없을 때만 기록한다 (중복 처리로 같은 건이 여러 번 쌓이지 않게). @return 기록했으면 true */
    boolean recordIfAbsent(String targetKey, String reasonCode, String detail, LocalDateTime at);
}
