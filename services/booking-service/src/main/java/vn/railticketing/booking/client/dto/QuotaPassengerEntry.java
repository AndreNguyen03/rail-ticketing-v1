package vn.railticketing.booking.client.dto;

public record QuotaPassengerEntry(
        String passengerIdNumber,
        int    ticketCount
) {}
