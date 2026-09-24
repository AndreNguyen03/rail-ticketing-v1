-- quota-service schema — Stage 7.
-- Owns per-passenger ticket quota state for sale windows (e.g. Tết).
-- No FK to bookingdb or inventorydb: cross-database JOINs are refused (ADR-0002).

CREATE TABLE sale_window (
    sale_window_id  BIGSERIAL    PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    starts_at       TIMESTAMPTZ  NOT NULL,
    ends_at         TIMESTAMPTZ  NOT NULL,
    quota_limit     INT          NOT NULL DEFAULT 4,
    CONSTRAINT chk_window_order   CHECK (ends_at > starts_at),
    CONSTRAINT chk_quota_positive CHECK (quota_limit > 0)
);

-- Seed: Tết 2026. Additional windows go in V2__... migrations.
INSERT INTO sale_window (name, starts_at, ends_at, quota_limit)
VALUES ('Tết 2026', '2026-01-26 00:00:00+07', '2026-02-10 23:59:59+07', 4);

CREATE TABLE quota_reservation (
    reservation_id      UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    sale_window_id      BIGINT       NOT NULL REFERENCES sale_window(sale_window_id),
    passenger_id_number VARCHAR(12)  NOT NULL,
    from_station_index  SMALLINT     NOT NULL,
    to_station_index    SMALLINT     NOT NULL,
    -- Stores booking-service's idempotencyKey UUID (used as the quota reference key).
    booking_id          UUID         NOT NULL,
    ticket_count        INT          NOT NULL DEFAULT 1,
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE', 'RELEASED')),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_ticket_count_positive CHECK (ticket_count > 0)
);

-- Hot-path lookup: only ACTIVE rows matter for quota counting.
CREATE INDEX idx_quota_res_lookup ON quota_reservation
    (sale_window_id, passenger_id_number, from_station_index, to_station_index)
    WHERE status = 'ACTIVE';

-- Release by bookingId: DELETE /quota/reservations/{bookingId}
CREATE INDEX idx_quota_res_booking ON quota_reservation (booking_id)
    WHERE status = 'ACTIVE';

-- DB-level idempotency guard: one booking cannot produce two ACTIVE reservations
-- for the same passenger. Catches Redis-miss races (e.g. after Redis restart).
CREATE UNIQUE INDEX uq_quota_res_booking_passenger
    ON quota_reservation (booking_id, passenger_id_number)
    WHERE status = 'ACTIVE';
