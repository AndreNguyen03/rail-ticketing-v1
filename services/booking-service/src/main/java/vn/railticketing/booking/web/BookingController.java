package vn.railticketing.booking.web;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.railticketing.booking.service.BookingLifecycleService;
import vn.railticketing.booking.service.BookingService;
import vn.railticketing.booking.web.dto.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService          bookingService;
    private final BookingLifecycleService lifecycleService;

    public BookingController(BookingService bookingService,
                              BookingLifecycleService lifecycleService) {
        this.bookingService   = bookingService;
        this.lifecycleService = lifecycleService;
    }

    @PostMapping
    public ResponseEntity<BookingResponse> createBooking(
            @Valid @RequestBody CreateBookingRequest request,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(bookingService.createBooking(request, idempotencyKey));
    }

    @PostMapping("/{bookingId}/confirm")
    public ResponseEntity<BookingResponse> confirmBooking(
            @PathVariable UUID bookingId,
            @Valid @RequestBody ConfirmBookingRequest request,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey
    ) {
        return ResponseEntity.ok(bookingService.confirmBooking(bookingId, idempotencyKey));
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<BookingResponse> getBooking(@PathVariable UUID bookingId) {
        return ResponseEntity.ok(bookingService.getBooking(bookingId));
    }

    // Stage 9: refund a confirmed booking. Pass the departure time so fare-service
    // can calculate the correct refund percentage.
    @PostMapping("/{bookingId}/refund")
    public ResponseEntity<RefundResponse> refund(
            @PathVariable UUID bookingId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime departureAt) {
        return ResponseEntity.ok(lifecycleService.refund(bookingId, departureAt));
    }

    // Stage 9: exchange a ticket to a different berth (same or different trip).
    @PostMapping("/{bookingId}/exchange")
    public ResponseEntity<ExchangeResponse> exchange(
            @PathVariable UUID bookingId,
            @Valid @RequestBody ExchangeRequest request) {
        return ResponseEntity.ok(lifecycleService.exchange(bookingId, request));
    }
}
