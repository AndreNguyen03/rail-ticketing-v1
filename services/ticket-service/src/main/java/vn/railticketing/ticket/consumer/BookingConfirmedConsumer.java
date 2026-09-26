package vn.railticketing.ticket.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import vn.railticketing.ticket.domain.IssuedTicket;
import vn.railticketing.ticket.event.EventEnvelope;
import vn.railticketing.ticket.event.BookingConfirmedPayload;
import vn.railticketing.ticket.repository.IssuedTicketRepository;

import java.util.UUID;

@Component
public class BookingConfirmedConsumer {

    private static final Logger log = LoggerFactory.getLogger(BookingConfirmedConsumer.class);
    private static final TypeReference<EventEnvelope<BookingConfirmedPayload>> TYPE =
            new TypeReference<>() {};

    private final IssuedTicketRepository repository;
    private final ObjectMapper mapper;

    public BookingConfirmedConsumer(IssuedTicketRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper     = mapper;
    }

    @KafkaListener(topics = "booking.events.v1", groupId = "ticket-service")
    public void onBookingEvent(String payload) throws JsonProcessingException {
        EventEnvelope<BookingConfirmedPayload> envelope = mapper.readValue(payload, TYPE);

        if (!"BookingConfirmed".equals(envelope.eventType())) return;

        BookingConfirmedPayload confirmed = envelope.payload();
        UUID bookingId = confirmed.bookingId();

        // Idempotent: skip if already issued for this booking
        if (!repository.findByBookingId(bookingId).isEmpty()) {
            log.debug("ticket-service: tickets already issued for booking {}", bookingId);
            return;
        }

        for (BookingConfirmedPayload.TicketInfo t : confirmed.tickets()) {
            IssuedTicket ticket = IssuedTicket.issue(
                    t.ticketId(), bookingId, confirmed.tripId(),
                    t.berthId(), t.carriageNo(), t.berthNo(),
                    t.passengerName(), t.passengerIdNumber(),
                    t.passengerType(), t.priceVnd());
            repository.save(ticket);
        }

        log.info("ticket-service: issued {} ticket(s) for booking {}",
                confirmed.tickets().size(), bookingId);
    }
}
