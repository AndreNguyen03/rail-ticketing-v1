package vn.railticketing.schedule.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import vn.railticketing.schedule.exception.TripNotFoundException;

import java.net.URI;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(TripNotFoundException.class)
    ProblemDetail handleTripNotFound(TripNotFoundException ex, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("https://railticketing.vn/problems/trip-not-found"));
        pd.setTitle("Trip not found");
        pd.setInstance(URI.create(req.getRequestURI()));
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
