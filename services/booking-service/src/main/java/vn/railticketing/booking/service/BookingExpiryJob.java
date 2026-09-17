package vn.railticketing.booking.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.railticketing.booking.domain.Booking;
import vn.railticketing.booking.repository.BookingRepository;

import java.time.Instant;
import java.util.List;

/** Sweep overdue PENDING bookings: move to PAYMENT_FAILED so clients stop polling, berths already reclaimed by TTL. */
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
