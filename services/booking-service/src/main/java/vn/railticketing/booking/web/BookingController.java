package vn.railticketing.booking.web;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.railticketing.booking.service.BookingService;
import vn.railticketing.booking.web.dto.BookingResponse;
import vn.railticketing.booking.web.dto.ConfirmBookingRequest;
import vn.railticketing.booking.web.dto.CreateBookingRequest;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
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
}
