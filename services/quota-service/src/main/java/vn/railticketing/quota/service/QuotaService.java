package vn.railticketing.quota.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.railticketing.quota.domain.QuotaReservation;
import vn.railticketing.quota.domain.SaleWindow;
import vn.railticketing.quota.repository.QuotaReservationRepository;
import vn.railticketing.quota.repository.SaleWindowRepository;
import vn.railticketing.quota.web.dto.PassengerQuotaEntry;
import vn.railticketing.quota.web.dto.QuotaReserveRequest;
import vn.railticketing.quota.web.dto.QuotaReserveResponse;
import vn.railticketing.quota.web.dto.QuotaViolationDto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    private final SaleWindowRepository        windowRepo;
    private final QuotaReservationRepository  reservationRepo;
    private final RedisTemplate<String, String> redis;
    private final RedisScript<List<Object>>    checkAndReserveScript;
    private final RedisScript<Long>            releaseScript;

    @Value("${quota.redis.enabled:false}")
    private boolean redisEnabled;

    public QuotaService(SaleWindowRepository windowRepo,
                        QuotaReservationRepository reservationRepo,
                        RedisTemplate<String, String> redis,
                        RedisScript<List<Object>> checkAndReserveScript,
                        RedisScript<Long> releaseScript) {
        this.windowRepo           = windowRepo;
        this.reservationRepo      = reservationRepo;
        this.redis                = redis;
        this.checkAndReserveScript = checkAndReserveScript;
        this.releaseScript        = releaseScript;
    }

    // ── Reserve ───────────────────────────────────────────────────────────────

    @Transactional
    public QuotaReserveResponse reserve(QuotaReserveRequest request) {
        Optional<SaleWindow> windowOpt = windowRepo.findActiveAt(Instant.now());
        if (windowOpt.isEmpty()) {
            log.debug("No active sale window — quota not enforced for booking {}", request.bookingId());
            return QuotaReserveResponse.allowed(null);
        }
        SaleWindow window = windowOpt.get();

        if (redisEnabled) {
            return reserveViaRedis(request, window);
        }
        return reserveViaDb(request, window);
    }

    private QuotaReserveResponse reserveViaRedis(QuotaReserveRequest request, SaleWindow window) {
        List<PassengerQuotaEntry> passengers = request.passengers();
        int n = passengers.size();

        List<String> keys = new ArrayList<>(n * 2);
        for (PassengerQuotaEntry p : passengers) {
            keys.add(quotaKey(window.getSaleWindowId(), request.fromStationIndex(),
                              request.toStationIndex(), p.passengerIdNumber()));
        }
        for (PassengerQuotaEntry p : passengers) {
            keys.add(idemKey(request.bookingId(), p.passengerIdNumber()));
        }

        long ttlSeconds = Math.max(1, window.getEndsAt().getEpochSecond() - Instant.now().getEpochSecond());

        List<Object> result = redis.execute(checkAndReserveScript, keys,
                String.valueOf(n),
                String.valueOf(window.getQuotaLimit()),
                String.valueOf(ttlSeconds),
                request.bookingId().toString());

        long ok  = toLong(result.get(0));
        String msg = result.get(1).toString();

        if (ok == 1L) {
            if (!"IDEMPOTENT_REPLAY".equals(msg)) {
                persistReservations(request, window);
            }
            return QuotaReserveResponse.allowed(window.getSaleWindowId());
        }

        // Quota exceeded: violator index is 1-based
        int violatorIdx = Integer.parseInt(msg) - 1;
        PassengerQuotaEntry violator = passengers.get(violatorIdx);
        int current = reservationRepo.sumActiveTickets(
                window.getSaleWindowId(), violator.passengerIdNumber(),
                request.fromStationIndex(), request.toStationIndex());

        return QuotaReserveResponse.denied(window.getSaleWindowId(),
                List.of(new QuotaViolationDto(violator.passengerIdNumber(),
                        current, window.getQuotaLimit())));
    }

    private QuotaReserveResponse reserveViaDb(QuotaReserveRequest request, SaleWindow window) {
        List<QuotaViolationDto> violations = new ArrayList<>();
        for (PassengerQuotaEntry p : request.passengers()) {
            int current = reservationRepo.sumActiveTickets(
                    window.getSaleWindowId(), p.passengerIdNumber(),
                    request.fromStationIndex(), request.toStationIndex());
            if (current + p.ticketCount() > window.getQuotaLimit()) {
                violations.add(new QuotaViolationDto(p.passengerIdNumber(), current, window.getQuotaLimit()));
            }
        }
        if (!violations.isEmpty()) {
            return QuotaReserveResponse.denied(window.getSaleWindowId(), violations);
        }

        persistReservations(request, window);
        return QuotaReserveResponse.allowed(window.getSaleWindowId());
    }

    private void persistReservations(QuotaReserveRequest request, SaleWindow window) {
        for (PassengerQuotaEntry p : request.passengers()) {
            try {
                QuotaReservation r = QuotaReservation.create(
                        window, p.passengerIdNumber(),
                        request.fromStationIndex(), request.toStationIndex(),
                        request.bookingId(), p.ticketCount());
                reservationRepo.save(r);
            } catch (DataIntegrityViolationException ex) {
                // uq_quota_res_booking_passenger: already reserved — idempotent, ignore.
                log.debug("quota_reservation already exists for booking {} passenger {} — idempotent",
                        request.bookingId(), p.passengerIdNumber());
            }
        }
    }

    // ── Release ───────────────────────────────────────────────────────────────

    @Transactional
    public void release(UUID bookingId) {
        List<QuotaReservation> active = reservationRepo.findByBookingIdAndStatus(bookingId, "ACTIVE");
        if (active.isEmpty()) return;

        if (redisEnabled) {
            releaseFromRedis(bookingId, active);
        }

        reservationRepo.releaseByBookingId(bookingId);
        log.info("quota released for booking {} ({} reservation(s))", bookingId, active.size());
    }

    private void releaseFromRedis(UUID bookingId, List<QuotaReservation> reservations) {
        int n = reservations.size();
        SaleWindow window = reservations.get(0).getSaleWindow();

        List<String> keys = new ArrayList<>(n * 2);
        for (QuotaReservation r : reservations) {
            keys.add(quotaKey(window.getSaleWindowId(), r.getFromStationIndex(),
                              r.getToStationIndex(), r.getPassengerIdNumber()));
        }
        for (QuotaReservation r : reservations) {
            keys.add(idemKey(bookingId, r.getPassengerIdNumber()));
        }

        redis.execute(releaseScript, keys, String.valueOf(n));
    }

    // ── Key builders ──────────────────────────────────────────────────────────

    public static String quotaKey(long windowId, short fromStation, short toStation, String idNumber) {
        return "quota:" + windowId + ":" + fromStation + ":" + toStation + ":" + idNumber;
    }

    public static String idemKey(UUID bookingId, String idNumber) {
        return "quota:idem:" + bookingId + ":" + idNumber;
    }

    private static long toLong(Object o) {
        if (o instanceof Long l)    return l;
        if (o instanceof Integer i) return i.longValue();
        return Long.parseLong(o.toString());
    }
}
