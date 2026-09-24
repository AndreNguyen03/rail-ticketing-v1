package vn.railticketing.booking.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import vn.railticketing.booking.exception.BookingNotFoundException;
import vn.railticketing.booking.exception.BookingNotConfirmableException;
import vn.railticketing.booking.exception.HoldExpiredException;
import vn.railticketing.booking.exception.QuotaExceededException;

import java.net.URI;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(HoldExpiredException.class)
    ProblemDetail handleHoldExpired(HoldExpiredException ex, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(URI.create("https://railticketing.vn/problems/hold-expired"));
        pd.setTitle("Hold expired");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("traceId", MDC.get("correlationId"));
        return pd;
    }

    @ExceptionHandler(BookingNotFoundException.class)
    ProblemDetail handleBookingNotFound(BookingNotFoundException ex, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("https://railticketing.vn/problems/booking-not-found"));
        pd.setTitle("Booking not found");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("traceId", MDC.get("correlationId"));
        return pd;
    }

    @ExceptionHandler(BookingNotConfirmableException.class)
    ProblemDetail handleNotConfirmable(BookingNotConfirmableException ex, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(URI.create("https://railticketing.vn/problems/booking-not-confirmable"));
        pd.setTitle("Booking not confirmable");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("traceId", MDC.get("correlationId"));
        return pd;
    }

    @ExceptionHandler(QuotaExceededException.class)
    ProblemDetail handleQuotaExceeded(QuotaExceededException ex, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(URI.create("https://railticketing.vn/problems/quota-exceeded"));
        pd.setTitle("Ticket quota exceeded");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("violations", ex.getViolations());
        pd.setProperty("traceId", MDC.get("correlationId"));
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleBadArgument(IllegalArgumentException ex, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setType(URI.create("https://railticketing.vn/problems/bad-request"));
        pd.setTitle("Bad request");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("traceId", MDC.get("correlationId"));
        return pd;
    }
}
