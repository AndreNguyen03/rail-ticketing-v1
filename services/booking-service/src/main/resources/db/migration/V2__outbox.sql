-- Stage 5 — Outbox pattern. Booking and event published atomically.
CREATE TABLE outbox (
    outbox_id      UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(50)  NOT NULL, -- e.g. 'booking'
    aggregate_id   UUID         NOT NULL,
    event_type     VARCHAR(50)  NOT NULL, -- e.g. 'BookingCreated', 'BookingConfirmed'
    payload        JSONB        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published      BOOLEAN      NOT NULL DEFAULT false,
    published_at   TIMESTAMPTZ
);
CREATE INDEX idx_outbox_published ON outbox (published, created_at) WHERE published = false;
