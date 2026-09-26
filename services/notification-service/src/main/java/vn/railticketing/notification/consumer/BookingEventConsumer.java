package vn.railticketing.notification.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import vn.railticketing.notification.event.EventEnvelope;

// Consumes booking domain events and mocks email/SMS notifications.
// In production: inject an email provider (SendGrid, SES) or SMS gateway (Twilio).
@Component
public class BookingEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(BookingEventConsumer.class);
    private static final TypeReference<EventEnvelope<JsonNode>> TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper;

    public BookingEventConsumer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @KafkaListener(topics = "booking.events.v1", groupId = "notification-service")
    public void onBookingEvent(String payload) throws JsonProcessingException {
        EventEnvelope<JsonNode> envelope = mapper.readValue(payload, TYPE);
        String eventType = envelope.eventType();
        JsonNode body = envelope.payload();

        switch (eventType) {
            case "BookingConfirmed" -> {
                String bookingId  = body.path("bookingId").asText();
                String email      = body.path("contactEmail").asText("(no email)");
                String name       = body.path("contactName").asText();
                int    tickets    = body.path("tickets").size();
                log.info("[NOTIFY] BookingConfirmed → email to {} ({}) — bookingId={} tickets={}",
                        email, name, bookingId, tickets);
                log.info("[NOTIFY] Subject: 'Your booking {} is confirmed! {} ticket(s) issued.'",
                        bookingId, tickets);
            }
            case "BookingPaymentFailed" -> {
                String bookingId = body.path("bookingId").asText();
                log.info("[NOTIFY] PaymentFailed → bookingId={} — notifying customer to retry", bookingId);
            }
            case "BookingCreated" -> {
                String bookingId = body.path("bookingId").asText();
                log.info("[NOTIFY] BookingCreated → bookingId={} — pending payment reminder scheduled", bookingId);
            }
            default -> log.debug("[NOTIFY] Unhandled event type: {}", eventType);
        }
    }

    @KafkaListener(topics = "booking.refunded.v1", groupId = "notification-service")
    public void onRefundEvent(String payload) throws JsonProcessingException {
        EventEnvelope<JsonNode> envelope = mapper.readValue(payload, TYPE);
        JsonNode body = envelope.payload();
        String bookingId      = body.path("bookingId").asText();
        long   refundAmount   = body.path("refundAmountVnd").asLong();
        log.info("[NOTIFY] BookingRefunded → bookingId={} refundAmount={}đ — notifying customer", bookingId, refundAmount);
        log.info("[NOTIFY] Subject: 'Your refund of {}đ for booking {} is being processed.'", refundAmount, bookingId);
    }
}
