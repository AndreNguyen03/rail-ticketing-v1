-- Inventory Service schema — Stage 0.
-- Owns berth availability state: bitmasks and holds.
-- berth_id mirrors schedule's berth_id but there is no FK — different database.

CREATE TABLE berth_inventory (
    berth_id      BIGINT       PRIMARY KEY,
    trip_id       BIGINT       NOT NULL,
    carriage_no   SMALLINT     NOT NULL,
    berth_no      SMALLINT     NOT NULL,
    berth_class   VARCHAR(20)  NOT NULL
                  CHECK (berth_class IN ('SOFT_SEAT','BERTH_6','BERTH_4')),
    level         SMALLINT,
    price_vnd     BIGINT       NOT NULL,
    occupied_mask INT          NOT NULL DEFAULT 0,
    held_mask     INT          NOT NULL DEFAULT 0,
    version       INT          NOT NULL DEFAULT 0
);

CREATE INDEX idx_berth_inventory_trip ON berth_inventory (trip_id);

-- hold stores journey_mask so releaseHold/commitHold do not have to recompute it.
CREATE TABLE hold (
    hold_id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id             BIGINT      NOT NULL,
    from_station_index  SMALLINT    NOT NULL,
    to_station_index    SMALLINT    NOT NULL,
    journey_mask        INT         NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    idempotency_key     UUID        NOT NULL UNIQUE
);

CREATE INDEX idx_hold_expires ON hold (expires_at);

CREATE TABLE hold_berth (
    hold_id   UUID    NOT NULL REFERENCES hold(hold_id) ON DELETE CASCADE,
    berth_id  BIGINT  NOT NULL REFERENCES berth_inventory(berth_id),
    PRIMARY KEY (hold_id, berth_id)
);
