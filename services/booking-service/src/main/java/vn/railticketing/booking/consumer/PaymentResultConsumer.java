package vn.railticketing.booking.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import vn.railticketing.booking.client.InventoryGateway;
import vn.railticketing.booking.event.BookingResultEvent;
import vn.railticketing.booking.event.EventEnvelope;
import vn.railticketing.booking.service.BookingPersistenceService;

import java.util.UUID;

@Component
public class PaymentResultConsumer {
    private static final Logger log = LoggerFactory.getLogger(PaymentResultConsumer.class);
    private static final TypeReference<EventEnvelope<BookingResultEvent>> ENVELOPE_TYPE = new TypeReference<>() {};

    private final BookingPersistenceService persistenceService;
    private final InventoryGateway inventoryGateway;
    private final ObjectMapper mapper;

    public PaymentResultConsumer(BookingPersistenceService persistenceService, InventoryGateway inventoryGateway, ObjectMapper mapper) {
        this.persistenceService = persistenceService;
        this.inventoryGateway = inventoryGateway;
        this.mapper = mapper;
    }

    @KafkaListener(topics = "payment.completed.v1", groupId = "booking-service")
    public void onPaymentResult(String payload) throws JsonProcessingException {
        // Deliberately not caught: a malformed envelope can't be fixed by retrying it,
        // so it's left to propagate to the container's error handler, which
        // dead-letters it instead of silently dropping it (see KafkaConfig).
        EventEnvelope<BookingResultEvent> envelope = mapper.readValue(payload, ENVELOPE_TYPE);
        BookingResultEvent msg = envelope.payload();
        UUID bookingId = UUID.fromString(msg.bookingId());
        String result = msg.result();

        var booking = persistenceService.findByIdOrThrow(bookingId);
        // Idempotent: Kafka redelivery never flips a terminal state.
        if (!"PENDING_PAYMENT".equals(booking.getStatus())) {
            log.info("Saga skip {} already {} (eventId={})", bookingId, booking.getStatus(), envelope.eventId());
            return;
        }

        if ("CONFIRMED".equals(result)) {
            // Commit berths BEFORE CONFIRMED: failure keeps PENDING for retry, no lost seat.
            if (booking.getHoldId() != null) {
                // Fallback already rethrew: Kafka redelivers for retry.
                inventoryGateway.commitHold(booking.getHoldId());
            }
            persistenceService.markConfirmed(bookingId);
            log.info("Saga CONFIRMED {} eventId={}", bookingId, envelope.eventId());
        } else {
            persistenceService.markPaymentFailed(bookingId);
            if (booking.getHoldId() != null) {
                // Fallback already swallowed: 15m TTL cleans up, no infinite retry.
                inventoryGateway.releaseHold(booking.getHoldId());
            }
            log.info("Saga PAYMENT_FAILED {} eventId={}", bookingId, envelope.eventId());
        }
    }
}
