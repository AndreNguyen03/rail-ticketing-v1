-- Stage 9: refund and exchange lifecycle columns.

ALTER TABLE booking
    ADD COLUMN refund_amount_vnd BIGINT,
    ADD COLUMN refunded_at       TIMESTAMPTZ;

ALTER TABLE ticket
    ADD COLUMN journey_mask        INT,           -- bitmask of legs this ticket covers (mirrors Hold.journeyMask)
    ADD COLUMN refund_amount_vnd   BIGINT,
    ADD COLUMN refunded_at         TIMESTAMPTZ,
    ADD COLUMN previous_berth_id   BIGINT;        -- populated on exchange, audit trail

-- 'ISSUED' stays the default; REFUNDED and EXCHANGED are new lifecycle states.
-- No CHECK constraint update needed: status is VARCHAR(20), open-ended.
