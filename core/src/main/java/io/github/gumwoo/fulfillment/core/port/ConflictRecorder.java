package io.github.gumwoo.fulfillment.core.port;

import java.time.LocalDateTime;

/** 사람이 봐야 하는 충돌 기록 포트 (conflict 테이블) */
public interface ConflictRecorder {

    void record(String targetKey, String reasonCode, String detail, LocalDateTime at);
}
