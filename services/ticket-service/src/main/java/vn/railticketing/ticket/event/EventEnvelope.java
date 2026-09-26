package vn.railticketing.ticket.event;

public record EventEnvelope<T>(String eventId, String eventType, String occurredAt, T payload) {}
