package vn.railticketing.payment.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import vn.railticketing.payment.event.BookingPaymentRequestedEvent;
import vn.railticketing.payment.event.EventEnvelope;
import vn.railticketing.payment.producer.PaymentResultProducer;

@Component
public class PaymentRequestConsumer {
    private static final Logger log = LoggerFactory.getLogger(PaymentRequestConsumer.class);
    private static final TypeReference<EventEnvelope<BookingPaymentRequestedEvent>> ENVELOPE_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper;
    private final PaymentResultProducer resultProducer;

    @Value("${payment.mock-success-rate:0.9}")
    private double successRate;

    public PaymentRequestConsumer(ObjectMapper mapper, PaymentResultProducer resultProducer) {
        this.mapper = mapper;
        this.resultProducer = resultProducer;
    }

    @KafkaListener(topics = "booking.payment-requested.v1", groupId = "payment-service")
    public void onBookingEvent(String payload) throws JsonProcessingException {
        // Deliberately not caught: a malformed envelope can't be fixed by retrying it,
        // so it's left to propagate to the container's error handler, which
        // dead-letters it instead of silently dropping it (see KafkaConfig).
        EventEnvelope<BookingPaymentRequestedEvent> envelope = mapper.readValue(payload, ENVELOPE_TYPE);
        BookingPaymentRequestedEvent request = envelope.payload();
        boolean success = Math.random() < successRate;
        String result = success ? "CONFIRMED" : "PAYMENT_FAILED";
        resultProducer.publish(request.bookingId(), result);
        log.info("PaymentRequestConsumer booking {} -> {} eventId={}", request.bookingId(), result, envelope.eventId());
    }
}
