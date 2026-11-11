package io.github.gumwoo.fulfillment.infra.config;

import io.github.gumwoo.fulfillment.core.cancel.CancelService;
import io.github.gumwoo.fulfillment.core.shipment.AlertBundler;
import io.github.gumwoo.fulfillment.core.shipment.InvoiceSyncService;
import io.github.gumwoo.fulfillment.core.shipment.TplEventService;
import io.github.gumwoo.fulfillment.infra.json.TplEventJson;
import io.github.gumwoo.fulfillment.infra.persistence.AlertRepository;
import io.github.gumwoo.fulfillment.infra.persistence.JpaInvoiceStore;
import io.github.gumwoo.fulfillment.infra.persistence.NotificationRepository;
import io.github.gumwoo.fulfillment.infra.persistence.TplCursorRepository;
import io.github.gumwoo.fulfillment.core.port.OrderSyncStore;
import io.github.gumwoo.fulfillment.infra.persistence.CancelCaseRepository;
import io.github.gumwoo.fulfillment.infra.persistence.ConflictRepository;
import io.github.gumwoo.fulfillment.infra.persistence.JpaCancelStore;
import io.github.gumwoo.fulfillment.infra.persistence.RecallTaskRepository;
import io.github.gumwoo.fulfillment.core.port.ShipmentStore;
import io.github.gumwoo.fulfillment.core.port.ShopbyClient;
import io.github.gumwoo.fulfillment.core.port.TplClient;
import io.github.gumwoo.fulfillment.core.port.TransactionRunner;
import io.github.gumwoo.fulfillment.core.shipment.ShipmentRequestService;
import io.github.gumwoo.fulfillment.infra.persistence.JpaShipmentStore;
import io.github.gumwoo.fulfillment.infra.persistence.ShipmentRepository;
import io.github.gumwoo.fulfillment.infra.persistence.UnlinkedRepository;
import io.github.gumwoo.fulfillment.infra.tpl.TplConfig;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 출고(3PL) 연동 서비스. 샵바이와 3PL 포트가 모두 필요하므로 worker·admin-api가 @Import 한다. */
@Configuration
@Import({CoreServicesConfig.class, ShopbyApiConfig.class, TplConfig.class})
public class ShipmentServicesConfig {

    @Bean
    public ShipmentStore shipmentStore(ShipmentRepository shipments, UnlinkedRepository unlinked) {
        return new JpaShipmentStore(shipments, unlinked);
    }

    @Bean
    public JpaCancelStore cancelStore(CancelCaseRepository cases, RecallTaskRepository recalls, ConflictRepository conflicts) {
        return new JpaCancelStore(cases, recalls, conflicts);
    }

    @Bean
    public CancelService cancelService(JpaCancelStore store, OrderSyncStore orders, TplClient tpl, ShopbyClient shopby,
                                       TransactionRunner tx, Clock clock) {
        return new CancelService(store, orders, tpl, shopby, store, tx, clock);
    }

    @Bean
    public JpaInvoiceStore invoiceStore(ShipmentRepository shipments,
                                        io.github.gumwoo.fulfillment.infra.persistence.OrderLineStateRepository lines,
                                        TplCursorRepository cursors, AlertRepository alerts,
                                        NotificationRepository notifications, JpaCancelStore conflicts) {
        return new JpaInvoiceStore(shipments, lines, cursors, alerts, notifications, conflicts);
    }

    @Bean
    public InvoiceSyncService invoiceSyncService(JpaInvoiceStore store, ShopbyClient shopby, JpaCancelStore conflicts,
                                                 TransactionRunner tx, Clock clock) {
        return new InvoiceSyncService(store, shopby, conflicts, store, tx, clock);
    }

    @Bean
    public TplEventService tplEventService(JpaInvoiceStore store, JpaCancelStore conflicts, TransactionRunner tx, Clock clock) {
        return new TplEventService(store, conflicts, store, tx, clock);
    }

    @Bean
    public AlertBundler alertBundler(JpaInvoiceStore store, TransactionRunner tx, Clock clock) {
        return new AlertBundler(store, tx, clock);
    }

    @Bean
    public TplEventJson tplEventJson(com.fasterxml.jackson.databind.ObjectMapper mapper) {
        return new TplEventJson(mapper);
    }

    @Bean
    public ShipmentRequestService shipmentRequestService(OrderSyncStore orders, ShipmentStore shipments, TplClient tpl,
                                                         ShopbyClient shopby, JpaCancelStore conflicts,
                                                         TransactionRunner tx, Clock clock) {
        return new ShipmentRequestService(orders, shipments, tpl, shopby, conflicts, tx, clock);
    }
}
