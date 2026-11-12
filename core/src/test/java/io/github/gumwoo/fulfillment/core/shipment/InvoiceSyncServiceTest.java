package io.github.gumwoo.fulfillment.core.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gumwoo.fulfillment.core.port.AlertStore;
import io.github.gumwoo.fulfillment.core.port.ConflictRecorder;
import io.github.gumwoo.fulfillment.core.port.InvoiceStore;
import io.github.gumwoo.fulfillment.core.port.InvoiceStore.PendingInvoice;
import io.github.gumwoo.fulfillment.core.port.ShopbyClient;
import io.github.gumwoo.fulfillment.core.port.ShopbyClient.InvoiceChange;
import io.github.gumwoo.fulfillment.core.port.ShopbyClient.InvoiceChangeResult;
import io.github.gumwoo.fulfillment.core.port.ShopbyClient.InvoiceFailure;
import io.github.gumwoo.fulfillment.core.port.TransactionRunner;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * 송장 반영 요청이 샵바이에 반영됐는데 응답을 잃으면, 다시 보낸 요청은 "이미 배송중"으로 거절된다.
 * 우리 주문 라인(웹훅·재조회로 받은 샵바이 상태)이 이미 배송중이고 송장이 같으면 반영 완료로 본다.
 */
class InvoiceSyncServiceTest {

    static final long SHIPPING = 9_000_001L;

    final List<Long> synced = new ArrayList<>();
    final List<Long> failed = new ArrayList<>();
    Set<Long> appliedOnShopby = Set.of();

    InvoiceSyncService service = new InvoiceSyncService(store(), shopbyRejectingAll(), stub(ConflictRecorder.class),
            stub(AlertStore.class), tx(), Clock.fixed(Instant.parse("2026-11-12T01:00:00Z"), ZoneId.of("Asia/Seoul")));

    @Test
    void 거절됐어도_이미_배송중이고_송장이_같으면_반영_완료로_본다() {
        appliedOnShopby = Set.of(SHIPPING);

        InvoiceSyncService.Result r = service.syncPending();

        assertThat(synced).containsExactly(SHIPPING);
        assertThat(failed).isEmpty();
        assertThat(r.failed()).isZero();
    }

    @Test
    void 아직_반영되지_않은_배송의_거절은_실패로_센다() {
        InvoiceSyncService.Result r = service.syncPending();

        assertThat(failed).containsExactly(SHIPPING);
        assertThat(synced).isEmpty();
        assertThat(r.failed()).isEqualTo(1);
    }

    private InvoiceStore store() {
        return (InvoiceStore) Proxy.newProxyInstance(InvoiceStore.class.getClassLoader(), new Class<?>[] {InvoiceStore.class},
                (p, m, args) -> switch (m.getName()) {
                    case "lockPending" -> List.of(new PendingInvoice(SHIPPING, "CJ", "INV1"));
                    case "alreadyApplied" -> appliedOnShopby.contains((Long) args[0]);
                    case "markSynced" -> {
                        synced.add((Long) args[0]);
                        yield null;
                    }
                    case "markSyncFailed" -> {
                        failed.add((Long) args[0]);
                        yield 1;
                    }
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private static ShopbyClient shopbyRejectingAll() {
        return (ShopbyClient) Proxy.newProxyInstance(ShopbyClient.class.getClassLoader(), new Class<?>[] {ShopbyClient.class},
                (p, m, args) -> {
                    if (!m.getName().equals("changeStatusByShippingNo")) {
                        throw new UnsupportedOperationException(m.getName());
                    }
                    @SuppressWarnings("unchecked")
                    List<InvoiceChange> changes = (List<InvoiceChange>) args[1];
                    List<InvoiceFailure> failures = changes.stream()
                            .map(c -> new InvoiceFailure(c, "OD_INVALID_STATUS", "이미 배송중")).toList();
                    return new InvoiceChangeResult(changes.size(), 0, failures.size(), failures);
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, args) -> {
            if (m.getReturnType() == boolean.class) {
                return true;
            }
            return null;
        });
    }

    private static TransactionRunner tx() {
        return new TransactionRunner() {
            @Override
            public <T> T inTransaction(Supplier<T> work) {
                return work.get();
            }
        };
    }
}
