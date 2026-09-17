package vn.railticketing.booking.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// resilience4j-spring-boot3 2.3.0 predates Boot 4's actuate.health rename, so
// its health indicator and metrics integration silently no-op here (verified:
// no circuitBreakers health component, no resilience4j_circuitbreaker_*
// series). This is the only state-transition visibility available until an
// upstream release supports Boot 4.
@Component
public class CircuitBreakerStateLogger {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerStateLogger.class);

    private final CircuitBreakerRegistry registry;

    public CircuitBreakerStateLogger(CircuitBreakerRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    void subscribe() {
        registry.getAllCircuitBreakers().forEach(cb ->
                cb.getEventPublisher().onStateTransition(event ->
                        log.warn("CircuitBreaker '{}' state transition: {} -> {}",
                                cb.getName(),
                                event.getStateTransition().getFromState(),
                                event.getStateTransition().getToState())));
    }
}
