package vn.railticketing.booking.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vn.railticketing.booking.client.dto.CreateHoldRequest;
import vn.railticketing.booking.client.dto.HoldResponse;
import vn.railticketing.booking.exception.HoldExpiredException;

import java.util.UUID;

// Circuit breaker for InventoryClient: fast-fail <50ms when inventory struggles, no 2s wait. Separate bean so Spring AOP intercepts.
@Component
public class InventoryGateway {

    private static final Logger log = LoggerFactory.getLogger(InventoryGateway.class);

    private final InventoryClient inventoryClient;

    public InventoryGateway(InventoryClient inventoryClient) {
        this.inventoryClient = inventoryClient;
    }

    @CircuitBreaker(name = "inventory", fallbackMethod = "getHoldFallback")
    public HoldResponse getHold(UUID holdId) {
        return inventoryClient.getHold(holdId);
    }

    // Stage 9: used by exchange flow to create a new hold for the replacement berth.
    @CircuitBreaker(name = "inventory", fallbackMethod = "createHoldFallback")
    public HoldResponse createHold(CreateHoldRequest request, UUID idempotencyKey) {
        return inventoryClient.createHold(request, idempotencyKey);
    }

    @CircuitBreaker(name = "inventory", fallbackMethod = "releaseHoldFallback")
    public void releaseHold(UUID holdId) {
        inventoryClient.releaseHold(holdId);
    }

    @CircuitBreaker(name = "inventory", fallbackMethod = "commitHoldFallback")
    public void commitHold(UUID holdId) {
        inventoryClient.commitHold(holdId);
    }

    // Treated as expired hold: caller already handles expiry.
    private HoldResponse getHoldFallback(UUID holdId, Exception e) {
        log.warn("getHold({}) short-circuited: {}", holdId, e.toString());
        throw new HoldExpiredException(holdId);
    }

    private HoldResponse createHoldFallback(CreateHoldRequest req, UUID idempotencyKey, Exception e) {
        log.warn("createHold(trip={}) short-circuited: {}", req.tripId(), e.toString());
        throw new HoldExpiredException(idempotencyKey); // reuse same exception class
    }

    // Best-effort: swallow error, hold TTL reclaims berths.
    private void releaseHoldFallback(UUID holdId, Exception e) {
        log.warn("releaseHold({}) short-circuited, hold TTL will clean up: {}", holdId, e.toString());
    }

    // Must not swallow: payment taken, rethrow for Kafka redelivery.
    private void commitHoldFallback(UUID holdId, Exception e) {
        log.warn("commitHold({}) short-circuited, will retry via Kafka redelivery: {}", holdId, e.toString());
        throw new IllegalStateException("commitHold short-circuited, retry", e);
    }
}
