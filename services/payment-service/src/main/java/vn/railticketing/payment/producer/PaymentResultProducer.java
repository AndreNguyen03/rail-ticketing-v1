package vn.railticketing.payment.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import vn.railticketing.payment.event.BookingResultEvent;
import vn.railticketing.payment.event.EventEnvelope;

import java.time.Instant;
import java.util.UUID;

@Component
public class PaymentResultProducer {
    private static final Logger log = LoggerFactory.getLogger(PaymentResultProducer.class);
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper mapper;

    public PaymentResultProducer(@Autowired(required = false) KafkaTemplate<String, String> kafkaTemplate, ObjectMapper mapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.mapper = mapper;
    }

    public void publish(String bookingId, String result) throws JsonProcessingException {
        if (kafkaTemplate == null) return;
        EventEnvelope<BookingResultEvent> envelope = new EventEnvelope<>(
                UUID.randomUUID().toString(), "PaymentCompleted", Instant.now().toString(),
                new BookingResultEvent(bookingId, result));
        kafkaTemplate.send("payment.completed.v1", bookingId, mapper.writeValueAsString(envelope));
        log.info("PaymentResultProducer published booking {} -> {} eventId={}", bookingId, result, envelope.eventId());
    }
}
