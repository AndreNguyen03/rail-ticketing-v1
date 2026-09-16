package vn.railticketing.booking.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.railticketing.booking.domain.Booking;
import vn.railticketing.booking.repository.BookingRepository;

import java.time.Instant;
import java.util.List;

/**
 * Stage 5 — Timeout sweeper for stuck PENDING_PAYMENT bookings.
 *
 * Problem: if payment-service dies permanently (not a transient crash), the
 * booking stays PENDING_PAYMENT forever — the outbox event is in Kafka but
 * payment never responds. The hold has already expired (inventory HoldExpiryJob
 * runs independently), but the booking record is orphaned.
 *
 * Fix: any booking whose expiresAt has passed while still PENDING_PAYMENT is
 * moved to PAYMENT_FAILED. The hold is already gone; this just closes the
 * booking record so clients stop polling and seats are not double-counted.
 */
@Component
public class BookingExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(BookingExpiryJob.class);

    private final BookingRepository bookingRepository;
    private final BookingPersistenceService persistenceService;

    public BookingExpiryJob(BookingRepository bookingRepository,
                            BookingPersistenceService persistenceService) {
        this.bookingRepository    = bookingRepository;
        this.persistenceService   = persistenceService;
    }

    @Scheduled(fixedDelayString = "${booking.expiry.sweep-interval-ms:30000}")
    public void sweep() {
        List<Booking> expired = bookingRepository.findExpiredPending(Instant.now());
        if (expired.isEmpty()) return;

        int failed = 0;
        for (Booking booking : expired) {
            try {
                persistenceService.markPaymentFailed(booking.getBookingId());
                failed++;
            } catch (Exception e) {
                log.warn("BookingExpiryJob: could not expire booking {}: {}",
                        booking.getBookingId(), e.getMessage());
            }
        }
        log.info("BookingExpiryJob: swept {} expired PENDING_PAYMENT booking(s)", failed);
    }
}
