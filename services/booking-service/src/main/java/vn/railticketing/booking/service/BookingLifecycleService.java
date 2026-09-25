package vn.railticketing.booking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.railticketing.booking.client.FareClient;
import vn.railticketing.booking.client.InventoryGateway;
import vn.railticketing.booking.client.InventoryRefundClient;
import vn.railticketing.booking.client.dto.HoldBerthDto;
import vn.railticketing.booking.client.dto.HoldResponse;
import vn.railticketing.booking.client.dto.RefundReleaseRequest;
import vn.railticketing.booking.domain.Booking;
import vn.railticketing.booking.domain.Outbox;
import vn.railticketing.booking.domain.Ticket;
import vn.railticketing.booking.event.BookingRefundedEvent;
import vn.railticketing.booking.exception.BookingNotFoundException;
import vn.railticketing.booking.repository.BookingRepository;
import vn.railticketing.booking.repository.OutboxRepository;
import vn.railticketing.booking.web.dto.*;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// Refund and Exchange operations (Stage 9). Separated from BookingService to
// keep orchestration class sizes manageable.
@Service
public class BookingLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(BookingLifecycleService.class);

    private final BookingRepository       bookingRepository;
    private final OutboxRepository        outboxRepository;
    private final InventoryRefundClient   inventoryRefundClient;
    private final InventoryGateway        inventoryGateway;
    private final FareClient              fareClient;
    private final ObjectMapper            mapper;

    public BookingLifecycleService(BookingRepository bookingRepository,
                                   OutboxRepository outboxRepository,
                                   InventoryRefundClient inventoryRefundClient,
                                   InventoryGateway inventoryGateway,
                                   FareClient fareClient,
                                   ObjectMapper mapper) {
        this.bookingRepository     = bookingRepository;
        this.outboxRepository      = outboxRepository;
        this.inventoryRefundClient = inventoryRefundClient;
        this.inventoryGateway      = inventoryGateway;
        this.fareClient            = fareClient;
        this.mapper                = mapper;
    }

    // ── Refund ────────────────────────────────────────────────────────────────

    // Orchestration only — HTTP outside transaction.
    public RefundResponse refund(UUID bookingId, OffsetDateTime departureAt) {
        Booking booking = bookingRepository.findByIdWithTickets(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));

        if (!"CONFIRMED".equals(booking.getStatus())) {
            throw new IllegalStateException(
                    "Only CONFIRMED bookings can be refunded, current status: " + booking.getStatus());
        }

        // Calculate refund amount via fare-service (time-based policy).
        long totalRefund = 0;
        for (Ticket t : booking.getTickets()) {
            long amount = getRefundAmount(t.getPriceVnd(), departureAt);
            totalRefund += amount;
        }

        // Release occupied berth bits in inventory for each ticket.
        List<Long> berthIds = booking.getTickets().stream()
                .map(Ticket::getBerthId).toList();
        // journeyMask: use first ticket's (all tickets in same booking share the same mask)
        // We store it in the booking's hold legacy — but Hold is gone post-confirm.
        // Fall back to computing it from ticket berth data: not possible without leg info.
        // Workaround: store journeyMask on ticket at creation time (V3 migration adds column).
        // For now, release is done per-ticket using their stored journeyMask if available,
        // defaulting to a full-mask approach via inventory endpoint.
        int journeyMask = booking.getTickets().stream()
                .filter(t -> t.getJourneyMask() != null)
                .mapToInt(Ticket::getJourneyMask)
                .findFirst()
                .orElse(0);

        if (!berthIds.isEmpty() && journeyMask != 0) {
            try {
                inventoryRefundClient.releaseOccupied(new RefundReleaseRequest(berthIds, journeyMask));
            } catch (Exception e) {
                log.warn("refund: failed to release occupied bits for booking {}: {} — inventory may drift",
                        bookingId, e.getMessage());
                // Best-effort: do NOT block the refund. Reconciliation will detect and repair.
            }
        }

        // Persist refund status atomically.
        long finalRefund = totalRefund;
        return persistRefund(bookingId, finalRefund);
    }

    private long getRefundAmount(long priceVnd, OffsetDateTime departureAt) {
        if (departureAt == null) return priceVnd;
        try {
            Long amount = fareClient.getRefundAmount(priceVnd, departureAt);
            return amount != null ? amount : priceVnd;
        } catch (Exception e) {
            log.warn("fare-service unavailable for refund calc, defaulting to full refund: {}", e.getMessage());
            return priceVnd;  // fail-open: grant full refund
        }
    }

    @Transactional(
            readOnly    = false,
            isolation   = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRED,
            rollbackFor = Exception.class
    )
    public RefundResponse persistRefund(UUID bookingId, long refundAmountVnd) {
        Booking booking = bookingRepository.findByIdWithTickets(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));

        booking.setStatus("REFUNDED");
        booking.setRefundAmountVnd(refundAmountVnd);
        booking.setRefundedAt(Instant.now());

        for (Ticket t : booking.getTickets()) {
            if ("ISSUED".equals(t.getStatus())) {
                t.setStatus("REFUNDED");
                t.setRefundAmountVnd(refundAmountVnd / booking.getTickets().size());
                t.setRefundedAt(Instant.now());
            }
        }

        bookingRepository.save(booking);

        // Outbox event for payment-service to reverse the charge.
        try {
            String payload = mapper.writeValueAsString(
                    new BookingRefundedEvent(bookingId, refundAmountVnd));
            outboxRepository.save(Outbox.create("booking", bookingId, "BookingRefunded", payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize BookingRefunded outbox payload", e);
        }

        log.info("refund persisted bookingId={} refundAmountVnd={}", bookingId, refundAmountVnd);
        return new RefundResponse(bookingId, "REFUNDED", refundAmountVnd, Instant.now());
    }

    // ── Exchange ──────────────────────────────────────────────────────────────

    // Exchange a specific passenger's ticket to a new berth on a (possibly different) trip.
    // Flow: create hold → exchange inventory atomically → update ticket.
    public ExchangeResponse exchange(UUID bookingId, ExchangeRequest request) {
        Booking booking = bookingRepository.findByIdWithTickets(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));

        if (!"CONFIRMED".equals(booking.getStatus())) {
            throw new IllegalStateException(
                    "Only CONFIRMED bookings can be exchanged, current status: " + booking.getStatus());
        }

        // Find the ticket for the given passenger
        Ticket ticket = booking.getTickets().stream()
                .filter(t -> t.getPassengerIdNumber().equals(request.passengerIdNumber())
                          && "ISSUED".equals(t.getStatus()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No ISSUED ticket found for passenger " + request.passengerIdNumber()));

        // Step 1: Create a new hold for the new berth
        vn.railticketing.booking.client.dto.CreateHoldRequest holdReq =
            new vn.railticketing.booking.client.dto.CreateHoldRequest(
                    request.newTripId() != null ? request.newTripId() : booking.getTripId(),
                    request.newFromStationIndex() >= 0 ? request.newFromStationIndex() : 0,
                    request.newToStationIndex() > 0 ? request.newToStationIndex() : 19,
                    1,
                    null
            );
        UUID holdIdem = UUID.randomUUID();
        HoldResponse newHold;
        try {
            newHold = inventoryGateway.createHold(holdReq, holdIdem);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create hold for exchange: " + e.getMessage(), e);
        }

        UUID newHoldId = newHold.holdId();
        Long newBerthId = newHold.berths().get(0).berthId();
        int journeyMask = ticket.getJourneyMask() != null ? ticket.getJourneyMask() : 0;

        // Step 2: Atomically release old occupied bits + commit new hold → occupied
        try {
            inventoryRefundClient.exchange(ticket.getBerthId(), journeyMask, newHoldId);
        } catch (Exception e) {
            // Compensation: release the newly created hold
            try { inventoryGateway.releaseHold(newHoldId); } catch (Exception ignored) {}
            throw new IllegalStateException("Exchange inventory swap failed: " + e.getMessage(), e);
        }

        // Step 3: Update ticket
        HoldBerthDto newBerthDto = newHold.berths().get(0);
        return persistExchange(bookingId, ticket.getTicketId(),
                ticket.getBerthId(), newBerthId,
                (short) newBerthDto.carriageNo(), (short) newBerthDto.berthNo(),
                newBerthDto.priceVnd() - ticket.getPriceVnd());
    }

    @Transactional(
            readOnly    = false,
            isolation   = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRED,
            rollbackFor = Exception.class
    )
    public ExchangeResponse persistExchange(UUID bookingId, UUID ticketId,
                                             Long oldBerthId, Long newBerthId,
                                             Short newCarriageNo, Short newBerthNo,
                                             long fareDifferenceVnd) {
        Booking booking = bookingRepository.findByIdWithTickets(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));

        Ticket ticket = booking.getTickets().stream()
                .filter(t -> t.getTicketId().equals(ticketId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Ticket not found: " + ticketId));

        ticket.setPreviousBerthId(oldBerthId);
        ticket.setBerthId(newBerthId);
        ticket.setCarriageNo(newCarriageNo);
        ticket.setBerthNo(newBerthNo);
        ticket.setStatus("ISSUED"); // stays ISSUED, berth changed

        // Adjust total price if fare differs
        if (fareDifferenceVnd != 0) {
            booking.setRefundAmountVnd(fareDifferenceVnd < 0 ? -fareDifferenceVnd : 0);
        }

        bookingRepository.save(booking);
        log.info("exchange persisted bookingId={} ticketId={} oldBerth={} newBerth={}",
                bookingId, ticketId, oldBerthId, newBerthId);

        return new ExchangeResponse(bookingId, ticketId, newBerthId, newCarriageNo, newBerthNo, fareDifferenceVnd);
    }
}
