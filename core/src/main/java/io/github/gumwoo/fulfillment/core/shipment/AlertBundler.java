package io.github.gumwoo.fulfillment.core.shipment;

import io.github.gumwoo.fulfillment.core.port.AlertStore;
import io.github.gumwoo.fulfillment.core.port.AlertStore.Alert;
import io.github.gumwoo.fulfillment.core.port.TransactionRunner;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 경보를 묶어 알림 한 건으로 만든다 (설계 4.8: 10분 단위). 주기 작업이 10분마다 호출한다.
 * 실제 Slack 발송은 범위 밖이다(계정 없음). notification 테이블이 발송 대기열 역할을 한다.
 */
public class AlertBundler {

    private final AlertStore alerts;
    private final TransactionRunner tx;
    private final Clock clock;

    public AlertBundler(AlertStore alerts, TransactionRunner tx, Clock clock) {
        this.alerts = alerts;
        this.tx = tx;
        this.clock = clock;
    }

    /** @return 만든 알림 id, 보낼 경보가 없으면 -1 */
    public long flush() {
        return tx.inTransaction(() -> {
            List<Alert> pending = alerts.findUnnotified();
            if (pending.isEmpty()) {
                return -1L;
            }
            Map<String, Long> byType = pending.stream()
                    .collect(Collectors.groupingBy(Alert::type, TreeMap::new, Collectors.counting()));
            StringBuilder text = new StringBuilder("[주문·출고 동기화] 확인이 필요한 건 ").append(pending.size()).append("건\n");
            byType.forEach((type, n) -> text.append("- ").append(type).append(": ").append(n).append("건\n"));
            pending.stream().limit(20).forEach(a -> text.append("  · ").append(a.targetKey()).append(" ").append(a.message()).append('\n'));
            return alerts.createNotification(text.toString(), pending.stream().map(Alert::id).toList(),
                    LocalDateTime.now(clock));
        });
    }
}
