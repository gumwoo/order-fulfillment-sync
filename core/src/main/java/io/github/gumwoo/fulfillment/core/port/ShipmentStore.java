package io.github.gumwoo.fulfillment.core.port;

import io.github.gumwoo.fulfillment.core.port.TplClient.Status;
import java.time.LocalDateTime;
import java.util.Optional;

/** 출고(3PL) 쪽 상태 영속성 포트. 단계마다 표시해 같은 일을 두 번 하지 않는다. */
public interface ShipmentStore {

    /**
     * @param prepareOutcomeUnknown 이전 상품준비중 변경 호출의 결과를 모른다 (타임아웃·5xx). 샵바이에는 반영됐을 수 있다 (ADR-0010)
     */
    record Shipment(long orderProductOptionNo, Long deliveryNo, Status tplStatus, boolean registered,
                    boolean prepared, String invoiceNo, String deliveryCompanyType, boolean prepareOutcomeUnknown) {
    }

    Optional<Shipment> find(long orderProductOptionNo);

    /** {@code SELECT ... FOR UPDATE}. 트랜잭션 안에서 불러 같은 옵션의 단계 진행을 직렬화한다 (ADR-0010) */
    Optional<Shipment> lock(long orderProductOptionNo);

    void markRegistered(long orderProductOptionNo, Long deliveryNo, Status status, LocalDateTime at);

    void markPrepared(long orderProductOptionNo, LocalDateTime at);

    /** 상품준비중 변경 호출의 결과를 모른다고 기록한다. 호출 트랜잭션이 되돌아가도 남도록 따로 커밋한다 (ADR-0010) */
    void markPrepareOutcomeUnknown(long orderProductOptionNo, LocalDateTime at);

    void updateTplStatus(long orderProductOptionNo, Status status, LocalDateTime at);

    /** 열린(미해결) 미연동 건이 없을 때만 새로 기록한다 */
    void recordUnlinkedIfAbsent(long orderProductOptionNo, String reasonCode, String detail, LocalDateTime at);

    void resolveUnlinked(long orderProductOptionNo, String resolver, LocalDateTime at);
}
