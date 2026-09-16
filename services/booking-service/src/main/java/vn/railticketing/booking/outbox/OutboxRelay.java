package vn.railticketing.booking.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.railticketing.booking.domain.Outbox;
import vn.railticketing.booking.repository.OutboxRepository;

import java.util.List;

@Component
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxRelay(OutboxRepository outboxRepository, @Autowired(required = false) KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${outbox.relay.interval-ms:500}")
    @Transactional
    public void relay() {
        if (kafkaTemplate == null) return;
        List<Outbox> batch = outboxRepository.findTop100ByPublishedFalseOrderByCreatedAtAsc();
        if (batch.isEmpty()) return;
        for (Outbox o : batch) {
            try {
                // booking.payment.request: payment-service consume.
                // booking.events: ticket/notification tương lai consume (BookingCreated/Confirmed/...).
                // payment.results do payment-service tự publish, booking-service KHÔNG publish lại.
                String topic = switch (o.getEventType()) {
                    case "BookingPaymentRequested" -> "booking.payment.request";
                    default -> "booking.events";
                };
                kafkaTemplate.send(topic, o.getAggregateId().toString(), o.getPayload()).get();
                o.setPublished(true);
                outboxRepository.save(o);
                log.info("OutboxRelay published {} {} -> {}", o.getEventType(), o.getAggregateId(), topic);
            } catch (Exception e) {
                log.warn("OutboxRelay failed {}: {}", o.getOutboxId(), e.getMessage());
            }
        }
    }
}
