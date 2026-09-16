package vn.railticketing.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class PaymentProcessor {
    private static final Logger log = LoggerFactory.getLogger(PaymentProcessor.class);
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper mapper;

    @Value("${payment.mock-success-rate:0.9}")
    private double successRate;

    public PaymentProcessor(@Autowired(required = false) KafkaTemplate<String, String> kafkaTemplate, ObjectMapper mapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.mapper = mapper;
    }

    public record BookingRequest(String bookingId) {}
    public record BookingResult(String bookingId, String result) {}

    @KafkaListener(topics = "booking.payment.request", groupId = "payment-service")
    public void onBookingEvent(String payload) {
        if (kafkaTemplate == null) return;
        try {
            BookingRequest request = mapper.readValue(payload, BookingRequest.class);
            boolean success = Math.random() < successRate;
            String result = success ? "CONFIRMED" : "PAYMENT_FAILED";
            BookingResult response = new BookingResult(request.bookingId(), result);
            String out = mapper.writeValueAsString(response);
            kafkaTemplate.send("payment.results", request.bookingId(), out);
            log.info("PaymentProcessor booking {} -> {}", request.bookingId(), result);
        } catch (Exception e) {
            log.warn("PaymentProcessor parse failed: {}", e.getMessage());
        }
    }
}
