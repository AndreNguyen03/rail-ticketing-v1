package vn.railticketing.booking.exception;

import vn.railticketing.booking.client.dto.QuotaViolation;

import java.util.List;

public class QuotaExceededException extends RuntimeException {

    private final List<QuotaViolation> violations;

    public QuotaExceededException(List<QuotaViolation> violations) {
        super("Ticket quota exceeded for " + violations.size() + " passenger(s)");
        this.violations = violations;
    }

    public List<QuotaViolation> getViolations() { return violations; }
}
