package vn.railticketing.booking.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import vn.railticketing.booking.client.InventoryClient;
import vn.railticketing.booking.service.BookingPersistenceService;

import java.util.UUID;

@Component
public class PaymentResultListener {
    private static final Logger log = LoggerFactory.getLogger(PaymentResultListener.class);
    private final BookingPersistenceService persistenceService;
    private final InventoryClient inventoryClient;
    private final ObjectMapper mapper;

    public PaymentResultListener(BookingPersistenceService persistenceService, InventoryClient inventoryClient, ObjectMapper mapper) {
        this.persistenceService = persistenceService;
        this.inventoryClient = inventoryClient;
        this.mapper = mapper;
    }

    public record BookingResult(String bookingId, String result) {}

    @KafkaListener(topics = "payment.results", groupId = "booking-service")
    public void onPaymentResult(String payload) {
        BookingResult msg;
        try {
            msg = mapper.readValue(payload, BookingResult.class);
        } catch (Exception e) {
            log.warn("PaymentResultListener parse failed, skip (poison): {}", e.getMessage());
            return;
        }
        UUID bookingId = UUID.fromString(msg.bookingId());
        String result = msg.result();

        var booking = persistenceService.findByIdOrThrow(bookingId);
        // Idempotent consumer: Kafka redelivery must not flip a terminal state
        if (!"PENDING_PAYMENT".equals(booking.getStatus())) {
            log.info("Saga skip {} already {}", bookingId, booking.getStatus());
            return;
        }

        if ("CONFIRMED".equals(result)) {
            // Commit inventory TRƯỚC khi mark CONFIRMED: nếu commit fail thì booking
            // vẫn PENDING để Kafka retry, tránh CONFIRMED nhưng mất ghế.
            if (booking.getHoldId() != null) {
                try {
                    inventoryClient.commitHold(booking.getHoldId());
                } catch (Exception e) {
                    log.warn("Saga {} commitHold failed, retry later: {}", bookingId, e.getMessage());
                    throw new IllegalStateException("commitHold failed, retry", e);
                }
            }
            persistenceService.markConfirmed(bookingId);
            log.info("Saga CONFIRMED {}", bookingId);
        } else {
            persistenceService.markPaymentFailed(bookingId);
            if (booking.getHoldId() != null) {
                try {
                    inventoryClient.releaseHold(booking.getHoldId());
                } catch (Exception e) {
                    // Release fail thì HoldExpiryJob (TTL 15p) dọn — không retry vô hạn
                    log.warn("Saga {} releaseHold failed, TTL will clean: {}", bookingId, e.getMessage());
                }
            }
            log.info("Saga PAYMENT_FAILED {}", bookingId);
        }
    }
}
