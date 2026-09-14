package vn.railticketing.inventory.exception;

import java.util.UUID;

public class HoldNotFoundException extends RuntimeException {

    public HoldNotFoundException(UUID holdId) {
        super("Hold " + holdId + " not found or already expired");
    }
}
