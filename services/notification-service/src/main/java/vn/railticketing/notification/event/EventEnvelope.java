package vn.railticketing.notification.event;

public record EventEnvelope<T>(String eventId, String eventType, String occurredAt, T payload) {}
