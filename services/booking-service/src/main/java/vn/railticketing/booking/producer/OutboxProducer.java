package vn.railticketing.booking.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.railticketing.booking.domain.Outbox;
import vn.railticketing.booking.event.EventEnvelope;
import vn.railticketing.booking.repository.OutboxRepository;

import java.util.List;

@Component
public class OutboxProducer {
    private static final Logger log = LoggerFactory.getLogger(OutboxProducer.class);
    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper mapper;

    public OutboxProducer(OutboxRepository outboxRepository, @Autowired(required = false) KafkaTemplate<String, String> kafkaTemplate, ObjectMapper mapper) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.mapper = mapper;
    }

    // Topic per docs/01-domain-model.md §"Code convention": <domain>.<event, past tense>.v<N>.
    private static String topicFor(String eventType) {
        return switch (eventType) {
            case "BookingPaymentRequested" -> "booking.payment-requested.v1";
            // Other booking domain events (BookingCreated/Confirmed/PaymentFailed) share one
            // bucket topic until a real consumer (ticket/notification-service, stage 6+)
            // needs them split — no point pre-splitting a topic nothing reads yet.
            default -> "booking.events.v1";
        };
    }

    @Scheduled(fixedDelayString = "${outbox.relay.interval-ms:500}")
    @Transactional
    public void relay() {
        if (kafkaTemplate == null) return;
        List<Outbox> batch = outboxRepository.findTop100ByPublishedFalseOrderByCreatedAtAsc();
        if (batch.isEmpty()) return;
        for (Outbox o : batch) {
            try {
                String topic = topicFor(o.getEventType());
                // Domain payload is already stored as JSON — re-wrapped as JsonNode
                // rather than deserialized to a concrete type this class doesn't know.
                EventEnvelope<JsonNode> envelope = new EventEnvelope<>(
                        o.getOutboxId().toString(), o.getEventType(), o.getCreatedAt().toString(),
                        mapper.readTree(o.getPayload()));
                kafkaTemplate.send(topic, o.getAggregateId().toString(), mapper.writeValueAsString(envelope)).get();
                o.setPublished(true);
                outboxRepository.save(o);
                log.info("OutboxProducer published {} {} eventId={} -> {}", o.getEventType(), o.getAggregateId(), o.getOutboxId(), topic);
            } catch (JsonProcessingException e) {
                // A stored outbox row that never parses/serializes will never succeed on
                // retry either — log loudly instead of retrying it forever every 500ms.
                log.error("OutboxProducer malformed payload, will keep retrying (fix data or this loops): {}", o.getOutboxId(), e);
            } catch (Exception e) {
                log.warn("OutboxProducer failed {}: {}", o.getOutboxId(), e.getMessage());
            }
        }
    }
}
