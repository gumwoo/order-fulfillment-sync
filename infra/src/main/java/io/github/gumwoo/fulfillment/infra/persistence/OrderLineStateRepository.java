package io.github.gumwoo.fulfillment.infra.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderLineStateRepository extends JpaRepository<OrderLineStateEntity, Long> {

    List<OrderLineStateEntity> findByDeliveryNo(Long deliveryNo);
}
