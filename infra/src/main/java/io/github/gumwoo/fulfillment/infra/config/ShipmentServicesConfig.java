package io.github.gumwoo.fulfillment.infra.config;

import io.github.gumwoo.fulfillment.core.cancel.CancelService;
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
    public CancelService cancelService(JpaCancelStore store, TplClient tpl, ShopbyClient shopby, TransactionRunner tx,
                                       Clock clock) {
        return new CancelService(store, tpl, shopby, store, tx, clock);
    }

    @Bean
    public ShipmentRequestService shipmentRequestService(OrderSyncStore orders, ShipmentStore shipments, TplClient tpl,
                                                         ShopbyClient shopby, JpaCancelStore conflicts,
                                                         TransactionRunner tx, Clock clock) {
        return new ShipmentRequestService(orders, shipments, tpl, shopby, conflicts, tx, clock);
    }
}
