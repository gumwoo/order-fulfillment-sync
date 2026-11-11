package io.github.gumwoo.fulfillment.infra.config;

import io.github.gumwoo.fulfillment.core.order.ApplyStatusChangeService;
import io.github.gumwoo.fulfillment.core.port.OrderSyncStore;
import io.github.gumwoo.fulfillment.core.port.TransactionRunner;
import io.github.gumwoo.fulfillment.infra.json.OrderStatusChangeJson;
import io.github.gumwoo.fulfillment.infra.persistence.ConflictRepository;
import io.github.gumwoo.fulfillment.infra.persistence.JpaOrderSyncStore;
import io.github.gumwoo.fulfillment.infra.persistence.OrderLineStateRepository;
import io.github.gumwoo.fulfillment.infra.persistence.ProcessedEventRepository;
import io.github.gumwoo.fulfillment.infra.persistence.StatusHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/** core의 프레임워크 없는 서비스를 포트 구현과 묶어 빈으로 만든다. */
@Configuration
@Import({PersistenceConfig.class, ClockConfig.class})
public class CoreServicesConfig {

    @Bean
    public TransactionRunner transactionRunner(TransactionTemplate template) {
        return new SpringTransactionRunner(template);
    }

    @Bean
    public OrderSyncStore orderSyncStore(ProcessedEventRepository processedEvents, OrderLineStateRepository lines,
                                         StatusHistoryRepository history, ConflictRepository conflicts) {
        return new JpaOrderSyncStore(processedEvents, lines, history, conflicts);
    }

    @Bean
    public ApplyStatusChangeService applyStatusChangeService(OrderSyncStore store, TransactionRunner tx, Clock clock) {
        return new ApplyStatusChangeService(store, tx, clock);
    }

    @Bean
    public OrderStatusChangeJson orderStatusChangeJson(ObjectMapper objectMapper) {
        return new OrderStatusChangeJson(objectMapper);
    }
}
