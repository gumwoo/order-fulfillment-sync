package io.github.gumwoo.fulfillment.core.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gumwoo.fulfillment.core.order.OrderLineState;
import io.github.gumwoo.fulfillment.core.order.OrderStatus;
import io.github.gumwoo.fulfillment.core.port.ConflictRecorder;
import io.github.gumwoo.fulfillment.core.port.OrderSyncStore;
import io.github.gumwoo.fulfillment.core.port.ShipmentStore;
import io.github.gumwoo.fulfillment.core.port.ShipmentStore.Shipment;
import io.github.gumwoo.fulfillment.core.port.ShopbyClient;
import io.github.gumwoo.fulfillment.core.port.TplClient;
import io.github.gumwoo.fulfillment.core.port.TransactionRunner;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ADR-0010: 상품준비중 변경 호출이 샵바이에 반영됐는데 응답을 잃으면(타임아웃), 다시 호출했을 때 400을 받는다.
 * 이 400은 우리 호출이 이미 반영된 결과이므로 conflict로 남기지 않는다. 처음 호출에서 받은 400은 그대로 conflict다.
 */
class ShipmentRequestServiceTest {

    static final long OPTION = 1_000_001L;

    final Map<Long, Shipment> shipments = new HashMap<>();
    final List<String> conflicts = new ArrayList<>();
    final OrderLineState payDone = new OrderLineState(OPTION, "DEMO1", 9_000_001L, OrderStatus.PAY_DONE, null, null,
            "티셔츠", "색상", "검정", 1, null, null, null, 0);

    /** 호출마다 할 일을 순서대로 정한 샵바이 */
    final List<Runnable> prepareCalls = new ArrayList<>();
    int prepareCount;

    ShipmentRequestService service = new ShipmentRequestService(orderStore(), shipmentStore(), tpl(), shopby(),
            conflictRecorder(), tx(), Clock.fixed(Instant.parse("2026-11-12T01:00:00Z"), ZoneId.of("Asia/Seoul")));

    @Test
    void 응답을_잃은_호출_뒤의_400은_충돌이_아니고_상품준비중으로_끝낸다() {
        prepareCalls.add(() -> {
            throw new RuntimeException("Read timed out"); // 샵바이에는 반영됨, 응답만 잃음
        });
        prepareCalls.add(() -> {
            throw new ShopbyClient.ApiException(400, "{\"code\":\"OD_INVALID_STATUS\"}");
        });

        assertThatThrownBy(() -> service.ensureRequested(OPTION)).hasMessageContaining("Read timed out");
        assertThat(shipments.get(OPTION).prepareOutcomeUnknown()).isTrue();
        assertThat(shipments.get(OPTION).prepared()).isFalse();

        assertThat(service.ensureRequested(OPTION)).isEqualTo(ShipmentRequestService.Result.REQUESTED);
        assertThat(conflicts).isEmpty();
        assertThat(shipments.get(OPTION).prepared()).isTrue();
        assertThat(prepareCount).isEqualTo(2);
    }

    @Test
    void 처음_호출에서_받은_400은_충돌로_남긴다() {
        prepareCalls.add(() -> {
            throw new ShopbyClient.ApiException(400, "{\"code\":\"OD_INVALID_STATUS\"}");
        });

        service.ensureRequested(OPTION);

        assertThat(conflicts).containsExactly(ShipmentRequestService.CONFLICT_PREPARE_REJECTED);
    }

    @Test
    void 레이트_리밋은_반영되지_않은_것이므로_결과_모름으로_기록하지_않는다() {
        prepareCalls.add(() -> {
            throw new ShopbyClient.RateLimitedException("429");
        });
        prepareCalls.add(() -> {
            throw new ShopbyClient.ApiException(400, "{\"code\":\"OD_INVALID_STATUS\"}");
        });

        assertThatThrownBy(() -> service.ensureRequested(OPTION)).isInstanceOf(ShopbyClient.RateLimitedException.class);
        assertThat(shipments.get(OPTION).prepareOutcomeUnknown()).isFalse();

        service.ensureRequested(OPTION);
        assertThat(conflicts).containsExactly(ShipmentRequestService.CONFLICT_PREPARE_REJECTED);
    }

    // ---- 가짜 포트 ----

    private Shipment row(long no) {
        return shipments.getOrDefault(no, new Shipment(no, null, null, false, false, null, null, false));
    }

    private ShipmentStore shipmentStore() {
        return stub(ShipmentStore.class, (name, args) -> switch (name) {
            case "find", "lock" -> Optional.ofNullable(shipments.get((Long) args[0]));
            case "markRegistered" -> {
                Shipment s = row((Long) args[0]);
                shipments.put(s.orderProductOptionNo(), new Shipment(s.orderProductOptionNo(), (Long) args[1],
                        (TplClient.Status) args[2], true, s.prepared(), null, null, s.prepareOutcomeUnknown()));
                yield null;
            }
            case "markPrepared" -> {
                Shipment s = row((Long) args[0]);
                shipments.put(s.orderProductOptionNo(), new Shipment(s.orderProductOptionNo(), s.deliveryNo(),
                        s.tplStatus(), s.registered(), true, null, null, s.prepareOutcomeUnknown()));
                yield null;
            }
            case "markPrepareOutcomeUnknown" -> {
                Shipment s = row((Long) args[0]);
                shipments.put(s.orderProductOptionNo(), new Shipment(s.orderProductOptionNo(), s.deliveryNo(),
                        s.tplStatus(), s.registered(), s.prepared(), null, null, true));
                yield null;
            }
            case "resolveUnlinked" -> null;
            default -> throw new UnsupportedOperationException(name);
        });
    }

    private OrderSyncStore orderStore() {
        return stub(OrderSyncStore.class, (name, args) -> {
            if (name.equals("findLine")) {
                return Optional.of(payDone);
            }
            throw new UnsupportedOperationException(name);
        });
    }

    private TplClient tpl() {
        return stub(TplClient.class, (name, args) -> {
            if (name.equals("register")) {
                return new TplClient.Registration(true, TplClient.Status.REQUESTED, null, null);
            }
            throw new UnsupportedOperationException(name);
        });
    }

    private ShopbyClient shopby() {
        return stub(ShopbyClient.class, (name, args) -> {
            if (name.equals("prepareProduct")) {
                prepareCalls.get(prepareCount++).run();
                return null;
            }
            throw new UnsupportedOperationException(name);
        });
    }

    private ConflictRecorder conflictRecorder() {
        return stub(ConflictRecorder.class, (name, args) -> {
            if (name.equals("record")) {
                conflicts.add((String) args[1]);
                return null;
            }
            throw new UnsupportedOperationException(name);
        });
    }

    private static TransactionRunner tx() {
        return new TransactionRunner() {
            @Override
            public <T> T inTransaction(java.util.function.Supplier<T> work) {
                return work.get();
            }
        };
    }

    interface Handler {
        Object handle(String method, Object[] args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, Handler h) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, m, args) -> h.handle(m.getName(), args == null ? new Object[0] : args));
    }
}
