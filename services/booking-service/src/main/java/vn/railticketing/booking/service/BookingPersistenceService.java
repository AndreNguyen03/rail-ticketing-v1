package vn.railticketing.booking.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.railticketing.booking.client.dto.HoldBerthDto;
import vn.railticketing.booking.client.dto.HoldResponse;
import vn.railticketing.booking.domain.Booking;
import vn.railticketing.booking.domain.Outbox;
import vn.railticketing.booking.domain.Ticket;
import vn.railticketing.booking.exception.BookingNotFoundException;
import vn.railticketing.booking.repository.BookingRepository;
import vn.railticketing.booking.repository.OutboxRepository;
import vn.railticketing.booking.web.dto.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Owns all DB writes for booking-service.
// Kept separate from BookingService so @Transactional methods are called
// through the Spring proxy (not via this.method() self-invocation).
@Service
public class BookingPersistenceService {

    private final BookingRepository bookingRepository;
    private final OutboxRepository outboxRepository;

    public BookingPersistenceService(BookingRepository bookingRepository, OutboxRepository outboxRepository) {
        this.bookingRepository = bookingRepository;
        this.outboxRepository = outboxRepository;
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
        // Stage 5 outbox: same transaction as booking, relay will publish to Kafka
        String payload = "{\"bookingId\":\"" + saved.getBookingId() + "\",\"tripId\":" + saved.getTripId() + ",\"status\":\"" + saved.getStatus() + "\"}";
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
        String payload = "{\"bookingId\":\"" + saved.getBookingId() + "\",\"status\":\"CONFIRMED\"}";
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
        String payload = "{\"bookingId\":\"" + saved.getBookingId() + "\",\"status\":\"PAYMENT_FAILED\"}";
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
        // Idempotent confirm: gọi POST /confirm 2 lần khi đang PENDING chỉ publish 1 event
        if (outboxRepository.existsByAggregateIdAndEventTypeAndPublishedFalse(
                booking.getBookingId(), "BookingPaymentRequested")) {
            return booking;
        }
        String payload = "{\"bookingId\":\"" + booking.getBookingId() + "\"}";
        outboxRepository.save(Outbox.create("booking", booking.getBookingId(), "BookingPaymentRequested", payload));
        return booking;
    }
}
