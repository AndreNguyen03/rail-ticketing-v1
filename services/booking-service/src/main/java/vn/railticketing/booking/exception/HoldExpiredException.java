package vn.railticketing.booking.exception;

import java.util.UUID;

public class HoldExpiredException extends RuntimeException {

    public HoldExpiredException(UUID holdId) {
        super("Hold " + holdId + " has expired or does not exist");
    }
}
