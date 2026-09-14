package vn.railticketing.schedule.exception;

public class TripNotFoundException extends RuntimeException {

    public TripNotFoundException(Long tripId) {
        super("Trip " + tripId + " not found");
    }
}
