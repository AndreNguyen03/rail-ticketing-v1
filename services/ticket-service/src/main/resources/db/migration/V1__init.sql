-- ticket-service schema — Stage 11.
-- Owns issued ticket records independently of booking-service (separate bounded context).
-- Populated from BookingConfirmed Kafka events. No FK to bookingdb.

CREATE TABLE issued_ticket (
    ticket_id           UUID         PRIMARY KEY,
    booking_id          UUID         NOT NULL,
    trip_id             BIGINT       NOT NULL,
    berth_id            BIGINT       NOT NULL,
    carriage_no         SMALLINT     NOT NULL,
    berth_no            SMALLINT     NOT NULL,
    passenger_name      VARCHAR(100) NOT NULL,
    passenger_id_number VARCHAR(12)  NOT NULL,
    passenger_type      VARCHAR(20)  NOT NULL,
    price_vnd           BIGINT       NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'ISSUED',
    issued_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_issued_ticket_booking   ON issued_ticket (booking_id);
CREATE INDEX idx_issued_ticket_passenger ON issued_ticket (passenger_id_number);
