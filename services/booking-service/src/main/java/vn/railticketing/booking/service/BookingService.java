package vn.railticketing.booking.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vn.railticketing.booking.client.InventoryGateway;
import vn.railticketing.booking.client.dto.HoldResponse;
import vn.railticketing.booking.domain.Booking;
import vn.railticketing.booking.exception.BookingNotConfirmableException;
import vn.railticketing.booking.exception.HoldExpiredException;
import vn.railticketing.booking.web.dto.*;

import java.util.List;
import java.util.UUID;

// Orchestration only, no @Transactional: HTTP outside transactions to avoid holding connections.
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final InventoryGateway inventoryGateway;
    private final BookingPersistenceService persistenceService;

    public BookingService(InventoryGateway inventoryGateway,
                          BookingPersistenceService persistenceService) {
        this.inventoryGateway = inventoryGateway;
        this.persistenceService = persistenceService;
    }

    public BookingResponse createBooking(CreateBookingRequest request, UUID idempotencyKey) {
        // Idempotent: same key returns the old result.
        return persistenceService.findByIdempotencyKey(idempotencyKey)
                .map(this::toResponse)
                .orElseGet(() -> doCreateBooking(request, idempotencyKey));
    }

    private BookingResponse doCreateBooking(CreateBookingRequest request, UUID idempotencyKey) {
        // HTTP outside transaction: no connection held while waiting on network.
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
            // Compensate failed DB write: release hold, 15m TTL cleans up if that fails too.
            tryReleaseHold(hold.holdId());
            throw e;
        }

        return toResponse(booking);
    }

    public BookingResponse confirmBooking(UUID bookingId, UUID idempotencyKey) {
        Booking booking = persistenceService.findByIdOrThrow(bookingId);

        // Already processed: return as-is to block double confirm.
        if (!"PENDING_PAYMENT".equals(booking.getStatus())) {
            if ("CONFIRMED".equals(booking.getStatus()) || "PAYMENT_FAILED".equals(booking.getStatus())) {
                return toResponse(booking);
            }
            throw new BookingNotConfirmableException(bookingId, booking.getStatus());
        }

        // Async: write outbox then return PENDING, Kafka decides later.
        persistenceService.requestPayment(bookingId);
        log.info("confirmBooking async requested for {} (outbox)", bookingId);
        return toResponse(booking);
    }

    public BookingResponse getBooking(UUID bookingId) {
        return toResponse(persistenceService.findByIdOrThrow(bookingId));
    }

    private HoldResponse fetchHoldOrThrow(UUID holdId) {
        // Gateway already maps every failure to HoldExpiredException.
        return inventoryGateway.getHold(holdId);
    }

    private void tryReleaseHold(UUID holdId) {
        // Fallback already swallowed: TTL cleans up.
        inventoryGateway.releaseHold(holdId);
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
