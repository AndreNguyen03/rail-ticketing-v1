package vn.railticketing.booking.exception;

import java.util.UUID;

public class BookingNotConfirmableException extends RuntimeException {

    public BookingNotConfirmableException(UUID bookingId, String currentStatus) {
        super("Booking " + bookingId + " cannot be confirmed — current status: " + currentStatus);
    }
}
