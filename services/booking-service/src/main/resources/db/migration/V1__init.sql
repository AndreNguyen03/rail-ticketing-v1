-- Booking Service schema — Stage 0.
-- Owns order lifecycle and ticket records.
-- Does NOT own inventory state — that lives in inventorydb.

CREATE TABLE booking (
    booking_id       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    status           VARCHAR(20)  NOT NULL
                     CHECK (status IN ('PENDING_PAYMENT','CONFIRMED','PAYMENT_FAILED','EXPIRED','CANCELLED')),
    trip_id          BIGINT       NOT NULL,
    hold_id          UUID,                          -- null once confirmed or failed
    contact_name     VARCHAR(100) NOT NULL,
    contact_phone    VARCHAR(20)  NOT NULL,
    contact_email    VARCHAR(200),
    total_price_vnd  BIGINT       NOT NULL,
    expires_at       TIMESTAMPTZ,                   -- mirrors hold expiry; null after confirm
    idempotency_key  UUID         NOT NULL UNIQUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE ticket (
    ticket_id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id           UUID         NOT NULL REFERENCES booking(booking_id),
    berth_id             BIGINT       NOT NULL,
    carriage_no          SMALLINT     NOT NULL,
    berth_no             SMALLINT     NOT NULL,
    passenger_name       VARCHAR(100) NOT NULL,
    passenger_id_number  VARCHAR(12)  NOT NULL,
    passenger_type       VARCHAR(20)  NOT NULL,
    price_vnd            BIGINT       NOT NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'ISSUED'
);

CREATE INDEX idx_ticket_booking ON ticket (booking_id);
