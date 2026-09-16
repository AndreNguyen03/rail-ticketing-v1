package vn.railticketing.booking.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import vn.railticketing.booking.client.InventoryClient;
import vn.railticketing.booking.client.dto.HoldResponse;
import vn.railticketing.booking.domain.Booking;
import vn.railticketing.booking.exception.BookingNotConfirmableException;
import vn.railticketing.booking.exception.HoldExpiredException;
import vn.railticketing.booking.web.dto.*;

import java.util.List;
import java.util.UUID;

// Orchestration only — no @Transactional here.
// All DB writes go through BookingPersistenceService (separate bean → proxy is honoured).
// HTTP calls happen outside DB transactions to avoid holding connections during network I/O.
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final InventoryClient inventoryClient;
    private final BookingPersistenceService persistenceService;

    public BookingService(InventoryClient inventoryClient,
                          BookingPersistenceService persistenceService) {
        this.inventoryClient = inventoryClient;
        this.persistenceService = persistenceService;
    }

    public BookingResponse createBooking(CreateBookingRequest request, UUID idempotencyKey) {
        // Idempotency check — no transaction needed for a simple read
        return persistenceService.findByIdempotencyKey(idempotencyKey)
                .map(this::toResponse)
                .orElseGet(() -> doCreateBooking(request, idempotencyKey));
    }

    private BookingResponse doCreateBooking(CreateBookingRequest request, UUID idempotencyKey) {
        // HTTP call — outside any transaction
        HoldResponse hold = fetchHoldOrThrow(request.holdId());

        if (hold.berths().size() != request.passengers().size()) {
            throw new IllegalArgumentException(
                    "Passenger count (" + request.passengers().size() +
                    ") must match berth count in hold (" + hold.berths().size() + ")");
        }

        Booking booking;
        try {
            booking = persistenceService.persistNewBooking(request, hold, idempotencyKey);
        } catch (Exception e) {
            // Naive compensation: DB write failed — release the hold so berths return to inventory.
            // If this release call also fails, the hold TTL (15 min) cleans it up.
            tryReleaseHold(hold.holdId());
            throw e;
        }

        return toResponse(booking);
    }

    public BookingResponse confirmBooking(UUID bookingId, UUID idempotencyKey) {
        Booking booking = persistenceService.findByIdOrThrow(bookingId);

        // Already processed — idempotent
        if (!"PENDING_PAYMENT".equals(booking.getStatus())) {
            if ("CONFIRMED".equals(booking.getStatus()) || "PAYMENT_FAILED".equals(booking.getStatus())) {
                return toResponse(booking);
            }
            throw new BookingNotConfirmableException(bookingId, booking.getStatus());
        }

        // Stage 5 async: publish BookingPaymentRequested via outbox, return PENDING
        // PaymentProcessor (via Kafka) will decide success/fail and drive saga via payment.results
        persistenceService.requestPayment(bookingId);
        log.info("confirmBooking async requested for {} (outbox)", bookingId);
        return toResponse(booking);
    }

    public BookingResponse getBooking(UUID bookingId) {
        return toResponse(persistenceService.findByIdOrThrow(bookingId));
    }

    private HoldResponse fetchHoldOrThrow(UUID holdId) {
        try {
            return inventoryClient.getHold(holdId);
        } catch (RestClientException e) {
            throw new HoldExpiredException(holdId);
        }
    }

    private void tryReleaseHold(UUID holdId) {
        try { inventoryClient.releaseHold(holdId); }
        catch (RestClientException e) {
            log.warn("tryReleaseHold: could not release hold {} — TTL will clean up. {}", holdId, e.getMessage());
        }
    }

    private BookingResponse toResponse(Booking b) {
        ContactDto contact = new ContactDto(b.getContactName(), b.getContactPhone(), b.getContactEmail());

        List<PassengerDto> passengers = b.getTickets().stream()
                .map(t -> new PassengerDto(t.getPassengerName(), t.getPassengerIdNumber(), t.getPassengerType()))
                .toList();

        List<TicketDto> tickets = b.getTickets().stream()
                .map(t -> new TicketDto(
                        t.getTicketId(), t.getBerthId(),
                        t.getCarriageNo(), t.getBerthNo(),
                        t.getPassengerName(), t.getPriceVnd()))
                .toList();

        return new BookingResponse(
                b.getBookingId(), b.getStatus(), b.getTripId(), b.getHoldId(),
                b.getTotalPriceVnd(), contact, passengers, tickets,
                b.getExpiresAt(), b.getCreatedAt()
        );
    }
}
