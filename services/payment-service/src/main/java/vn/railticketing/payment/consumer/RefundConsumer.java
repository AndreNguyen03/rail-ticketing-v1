package vn.railticketing.payment.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import vn.railticketing.payment.event.BookingRefundedEvent;
import vn.railticketing.payment.event.EventEnvelope;

// Stage 9: consume refund events and mock VNPay/MoMo reversal.
// In production this would call the payment gateway's refund API.
@Component
public class RefundConsumer {

    private static final Logger log = LoggerFactory.getLogger(RefundConsumer.class);
    private static final TypeReference<EventEnvelope<BookingRefundedEvent>> TYPE =
            new TypeReference<>() {};

    private final ObjectMapper mapper;

    public RefundConsumer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @KafkaListener(topics = "booking.refunded.v1", groupId = "payment-service")
    public void onRefundEvent(String payload) throws JsonProcessingException {
        EventEnvelope<BookingRefundedEvent> envelope = mapper.readValue(payload, TYPE);
        BookingRefundedEvent event = envelope.payload();

        // Mock: always succeeds. Production: call VNPay/MoMo reverse-charge API.
        log.info("RefundConsumer: processing refund bookingId={} amount={}đ eventId={}",
                event.bookingId(), event.refundAmountVnd(), envelope.eventId());
        log.info("RefundConsumer: refund COMPLETED bookingId={} (mock)", event.bookingId());
    }
}
