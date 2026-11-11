package io.github.gumwoo.fulfillment.core.port;

import java.time.LocalDateTime;
import java.util.List;

/** 경보와 묶음 알림 영속성 포트 (설계 4.8: Slack 알림을 10분 단위로 묶어 보낸다) */
public interface AlertStore {

    record Alert(long id, String type, String targetKey, String message, LocalDateTime createdAt) {
    }

    void add(String type, String targetKey, String message, LocalDateTime at);

    List<Alert> findUnnotified();

    /** 알림 한 건을 만들고 포함된 경보를 발송 처리한다. @return 알림 id */
    long createNotification(String text, List<Long> alertIds, LocalDateTime at);
}
