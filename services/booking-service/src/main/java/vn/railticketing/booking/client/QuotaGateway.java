package vn.railticketing.booking.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import vn.railticketing.booking.client.dto.QuotaCheckRequest;
import vn.railticketing.booking.client.dto.QuotaCheckResponse;

import java.util.UUID;

// Circuit breaker for quota-service.
// Important: quota-service returns 409 when quota is exceeded. This is a BUSINESS
// response, not an infrastructure failure. The fallback detects 409 and returns
// a "denied" response so BookingService can reject the booking correctly.
// True infrastructure failures (connection refused, timeout) are fail-open: null
// response → BookingService logs QUOTA_FAIL_OPEN and allows the booking.
@Component
public class QuotaGateway {

    private static final Logger log = LoggerFactory.getLogger(QuotaGateway.class);

    private final QuotaClient   quotaClient;
    private final ObjectMapper  objectMapper;

    public QuotaGateway(QuotaClient quotaClient, ObjectMapper objectMapper) {
        this.quotaClient  = quotaClient;
        this.objectMapper = objectMapper;
    }

    @CircuitBreaker(name = "quota", fallbackMethod = "checkAndReserveFallback")
    public QuotaCheckResponse checkAndReserve(QuotaCheckRequest request) {
        return quotaClient.checkAndReserve(request);
    }

    // Best-effort: swallow error. Reconciliation corrects Redis within 60s.
    @CircuitBreaker(name = "quota", fallbackMethod = "releaseQuotaFallback")
    public void releaseQuota(UUID bookingId) {
        quotaClient.releaseQuota(bookingId);
    }

    private QuotaCheckResponse checkAndReserveFallback(QuotaCheckRequest req, Exception e) {
        // 409 Conflict = quota-service is healthy but quota is exceeded.
        // Parse the response body and return a "denied" response so BookingService
        // rejects the booking — this is NOT an infrastructure failure.
        if (e instanceof HttpClientErrorException.Conflict conflict) {
            try {
                return objectMapper.readValue(
                    conflict.getResponseBodyAsString(), QuotaCheckResponse.class);
            } catch (Exception parseEx) {
                log.warn("quota 409 body parse failed, treating as denied: {}", parseEx.getMessage());
                // Return a minimal denied response if parsing fails
                return new QuotaCheckResponse(false, null, java.util.List.of());
            }
        }

        // Infrastructure failure (connection refused, timeout, 5xx): fail-open.
        // A quota-service outage should not block all ticket purchases.
        log.warn("QUOTA_FAIL_OPEN bookingId={} passengers={} reason={}",
                req.bookingId(),
                req.passengers().stream().map(p -> p.passengerIdNumber()).toList(),
                e.toString());
        return null;   // null → BookingService proceeds without enforcement
    }

    private void releaseQuotaFallback(UUID bookingId, Exception e) {
        if (!(e instanceof HttpClientErrorException)) {
            log.warn("releaseQuota({}) short-circuited, reconciliation will correct: {}",
                    bookingId, e.toString());
        }
    }
}
