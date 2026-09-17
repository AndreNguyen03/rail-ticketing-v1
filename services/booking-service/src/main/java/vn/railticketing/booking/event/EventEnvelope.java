package vn.railticketing.booking.event;

// eventId/eventType/occurredAt envelope: docs/01-domain-model.md's convention
// (`domain.event.vN` topic) plus an eventId a consumer can dedupe/trace on and
// an occurredAt a trace can order by — the raw domain payload alone had neither.
public record EventEnvelope<T>(String eventId, String eventType, String occurredAt, T payload) {}
