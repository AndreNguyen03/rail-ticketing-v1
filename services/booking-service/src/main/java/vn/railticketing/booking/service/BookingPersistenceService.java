package vn.railticketing.booking.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.railticketing.booking.client.dto.HoldBerthDto;
import vn.railticketing.booking.client.dto.HoldResponse;
import vn.railticketing.booking.domain.Booking;
import vn.railticketing.booking.domain.Ticket;
import vn.railticketing.booking.exception.BookingNotFoundException;
import vn.railticketing.booking.repository.BookingRepository;
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

    public BookingPersistenceService(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
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

        return bookingRepository.save(booking);
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
        return bookingRepository.save(booking);
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
        return bookingRepository.save(booking);
    }
}
