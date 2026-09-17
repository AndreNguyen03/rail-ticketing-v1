package vn.railticketing.booking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.railticketing.booking.client.dto.HoldBerthDto;
import vn.railticketing.booking.client.dto.HoldResponse;
import vn.railticketing.booking.domain.Booking;
import vn.railticketing.booking.domain.Outbox;
import vn.railticketing.booking.domain.Ticket;
import vn.railticketing.booking.event.BookingConfirmedEvent;
import vn.railticketing.booking.event.BookingCreatedEvent;
import vn.railticketing.booking.event.BookingPaymentFailedEvent;
import vn.railticketing.booking.event.BookingPaymentRequestedEvent;
import vn.railticketing.booking.exception.BookingNotFoundException;
import vn.railticketing.booking.repository.BookingRepository;
import vn.railticketing.booking.repository.OutboxRepository;
import vn.railticketing.booking.web.dto.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Owns all DB writes: separate bean so Spring proxy applies @Transactional.
@Service
public class BookingPersistenceService {

    private final BookingRepository bookingRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper mapper;

    public BookingPersistenceService(BookingRepository bookingRepository, OutboxRepository outboxRepository, ObjectMapper mapper) {
        this.bookingRepository = bookingRepository;
        this.outboxRepository = outboxRepository;
        this.mapper = mapper;
    }

    private String toJson(Object payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox payload", e);
        }
    }

    @Transactional(
            readOnly    = true,
            isolation   = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRED
    )
    public Optional<Booking> findByIdempotencyKey(UUID idempotencyKey) {
        return bookingRepository.findByIdempotencyKeyWithTickets(idempotencyKey);
    }

    @Transactional(
            readOnly    = true,
            isolation   = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRED
    )
    public Booking findByIdOrThrow(UUID bookingId) {
        return bookingRepository.findByIdWithTickets(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
    }

    @Transactional(
            readOnly    = false,
            isolation   = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRED,
            rollbackFor = Exception.class
    )
    public Booking persistNewBooking(CreateBookingRequest request,
                                     HoldResponse hold,
                                     UUID idempotencyKey) {
        Booking booking = Booking.create(
                hold.tripId(),
                hold.holdId(),
                request.contact().fullName(),
                request.contact().phone(),
                request.contact().email(),
                hold.totalPriceVnd(),
                hold.expiresAt(),
                idempotencyKey
        );

        List<HoldBerthDto> berths = hold.berths();
        List<PassengerDto> passengers = request.passengers();

        for (int i = 0; i < passengers.size(); i++) {
            PassengerDto p = passengers.get(i);
            HoldBerthDto b = berths.get(i);
            booking.getTickets().add(Ticket.create(
                    booking,
                    b.berthId(),
                    (short) b.carriageNo(),
                    (short) b.berthNo(),
                    p.fullName(),
                    p.idNumber(),
                    p.passengerType(),
                    b.priceVnd()
            ));
        }

        Booking saved = bookingRepository.save(booking);
        // Outbox in same booking transaction: relay publishes to Kafka later.
        String payload = toJson(new BookingCreatedEvent(saved.getBookingId(), saved.getTripId(), saved.getStatus()));
        outboxRepository.save(Outbox.create("booking", saved.getBookingId(), "BookingCreated", payload));
        return saved;
    }

    @Transactional(
            readOnly    = false,
            isolation   = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRED,
            rollbackFor = Exception.class
    )
    public Booking markConfirmed(UUID bookingId) {
        Booking booking = bookingRepository.findByIdWithTickets(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
        booking.setStatus("CONFIRMED");
        booking.setHoldId(null);
        booking.setExpiresAt(null);
        Booking saved = bookingRepository.save(booking);
        String payload = toJson(new BookingConfirmedEvent(saved.getBookingId(), "CONFIRMED"));
        outboxRepository.save(Outbox.create("booking", saved.getBookingId(), "BookingConfirmed", payload));
        return saved;
    }

    @Transactional(
            readOnly    = false,
            isolation   = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRED,
            rollbackFor = Exception.class
    )
    public Booking markPaymentFailed(UUID bookingId) {
        Booking booking = bookingRepository.findByIdWithTickets(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
        booking.setStatus("PAYMENT_FAILED");
        booking.setHoldId(null);
        booking.setExpiresAt(null);
        Booking saved = bookingRepository.save(booking);
        String payload = toJson(new BookingPaymentFailedEvent(saved.getBookingId(), "PAYMENT_FAILED"));
        outboxRepository.save(Outbox.create("booking", saved.getBookingId(), "BookingPaymentFailed", payload));
        return saved;
    }

    @Transactional(
            readOnly    = false,
            isolation   = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRED,
            rollbackFor = Exception.class
    )
    public Booking requestPayment(UUID bookingId) {
        Booking booking = bookingRepository.findByIdWithTickets(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
        // Idempotent confirm: existing unpublished event means no new one.
        if (outboxRepository.existsByAggregateIdAndEventTypeAndPublishedFalse(
                booking.getBookingId(), "BookingPaymentRequested")) {
            return booking;
        }
        String payload = toJson(new BookingPaymentRequestedEvent(booking.getBookingId()));
        outboxRepository.save(Outbox.create("booking", booking.getBookingId(), "BookingPaymentRequested", payload));
        return booking;
    }
}
