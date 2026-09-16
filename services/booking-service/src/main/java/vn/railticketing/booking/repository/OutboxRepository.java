package vn.railticketing.booking.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.railticketing.booking.domain.Outbox;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<Outbox, UUID> {
    List<Outbox> findTop100ByPublishedFalseOrderByCreatedAtAsc();

    boolean existsByAggregateIdAndEventTypeAndPublishedFalse(UUID aggregateId, String eventType);
}
