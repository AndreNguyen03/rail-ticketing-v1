-- Schedule Service schema and seed data — Stage 0.
-- Owns the static catalog: stations, trains, trips, carriages, berths.
-- Berth inventory (bitmasks: occupiedMask, heldMask) lives in inventorydb.

-- ── Schema ───────────────────────────────────────────────────────────────────

CREATE TABLE station (
    station_code  VARCHAR(5)    PRIMARY KEY,
    station_name  VARCHAR(100)  NOT NULL
);

CREATE TABLE train (
    train_code   VARCHAR(10)   PRIMARY KEY,
    description  VARCHAR(200)
);

-- One specific run of a train on one calendar date.
CREATE TABLE trip (
    trip_id       BIGSERIAL    PRIMARY KEY,
    train_code    VARCHAR(10)  NOT NULL REFERENCES train(train_code),
    service_date  DATE         NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'SCHEDULED'
                  CHECK (status IN ('SCHEDULED','SELLING','CLOSED','DEPARTED','CANCELLED')),
    UNIQUE (train_code, service_date)
);

-- Ordered stops. station_index maps 1:1 onto the leg bitmask stored in inventorydb:
-- leg i runs between station_index i and i+1.
CREATE TABLE trip_stop (
    trip_id        BIGINT      NOT NULL REFERENCES trip(trip_id),
    station_index  SMALLINT    NOT NULL,   -- 0 = origin, n = terminus
    station_code   VARCHAR(5)  NOT NULL REFERENCES station(station_code),
    arrives_at     TIMESTAMPTZ,            -- null for the origin stop
    departs_at     TIMESTAMPTZ,            -- null for the terminus stop
    PRIMARY KEY (trip_id, station_index)
);

CREATE TABLE carriage (
    carriage_id   BIGSERIAL    PRIMARY KEY,
    trip_id       BIGINT       NOT NULL REFERENCES trip(trip_id),
    carriage_no   SMALLINT     NOT NULL,
    berth_class   VARCHAR(20)  NOT NULL
                  CHECK (berth_class IN ('SOFT_SEAT','BERTH_6','BERTH_4')),
    UNIQUE (trip_id, carriage_no)
);

-- Physical berth. level: null = seat; 1 = lower, 2 = middle, 3 = upper.
CREATE TABLE berth (
    berth_id     BIGSERIAL    PRIMARY KEY,
    trip_id      BIGINT       NOT NULL REFERENCES trip(trip_id),
    carriage_id  BIGINT       NOT NULL REFERENCES carriage(carriage_id),
    carriage_no  SMALLINT     NOT NULL,
    berth_no     SMALLINT     NOT NULL,
    berth_class  VARCHAR(20)  NOT NULL
                 CHECK (berth_class IN ('SOFT_SEAT','BERTH_6','BERTH_4')),
    level        SMALLINT,
    price_vnd    BIGINT       NOT NULL,
    UNIQUE (trip_id, carriage_no, berth_no)
);

CREATE INDEX idx_trip_search    ON trip      (service_date, status);
CREATE INDEX idx_trip_stop      ON trip_stop (trip_id);
CREATE INDEX idx_berth_trip     ON berth     (trip_id);
CREATE INDEX idx_berth_carriage ON berth     (carriage_id);

-- ── Stations — 20 stops on the North–South mainline ─────────────────────────

INSERT INTO station (station_code, station_name) VALUES
    ('HN',   'Hà Nội'),
    ('PHU',  'Phủ Lý'),
    ('NAM',  'Nam Định'),
    ('NBI',  'Ninh Bình'),
    ('THH',  'Thanh Hóa'),
    ('BSO',  'Bỉm Sơn'),
    ('VINH', 'Vinh'),
    ('DHO',  'Đồng Hới'),
    ('DHA',  'Đông Hà'),
    ('HUE',  'Huế'),
    ('DAN',  'Đà Nẵng'),
    ('TAM',  'Tam Kỳ'),
    ('QNG',  'Quảng Ngãi'),
    ('DIE',  'Diêu Trì'),
    ('TUY',  'Tuy Hòa'),
    ('NTR',  'Nha Trang'),
    ('TCH',  'Tháp Chàm'),
    ('BTH',  'Bình Thuận'),
    ('BHO',  'Biên Hòa'),
    ('SG',   'Sài Gòn');

-- ── Trains ───────────────────────────────────────────────────────────────────

INSERT INTO train (train_code, description) VALUES
    ('SE1', 'Tốc hành Hà Nội – Sài Gòn'),
    ('SE2', 'Tốc hành Sài Gòn – Hà Nội'),
    ('SE3', 'Tốc hành Hà Nội – Sài Gòn (đêm)'),
    ('SE4', 'Tốc hành Sài Gòn – Hà Nội (đêm)');

-- ── Trips, stops, carriages, berths ─────────────────────────────────────────
-- Carriage layout per trip (mirrors VR long-distance composition, ~506 berths):
--   carriages  1-2  : SOFT_SEAT  64 seats  × 2 =  128
--   carriages  3-7  : BERTH_6    42 berths × 5 =  210
--   carriages  8-13 : BERTH_4    28 berths × 6 =  168
--   total                                        506

DO $$
DECLARE
    se1  BIGINT;
    se2  BIGINT;
    c_id BIGINT;
    bno  SMALLINT;
BEGIN

    -- ── SE1  HN → SG  departs 06:00, arrives ~15:00 next day ────────────────
    INSERT INTO trip (train_code, service_date, status)
    VALUES ('SE1', '2026-02-14', 'SELLING')
    RETURNING trip_id INTO se1;

    INSERT INTO trip_stop (trip_id, station_index, station_code, arrives_at, departs_at) VALUES
        (se1,  0, 'HN',   NULL,                            '2026-02-14 06:00:00+07'),
        (se1,  1, 'PHU',  '2026-02-14 07:15:00+07',        '2026-02-14 07:17:00+07'),
        (se1,  2, 'NAM',  '2026-02-14 07:50:00+07',        '2026-02-14 07:55:00+07'),
        (se1,  3, 'NBI',  '2026-02-14 08:25:00+07',        '2026-02-14 08:30:00+07'),
        (se1,  4, 'THH',  '2026-02-14 09:45:00+07',        '2026-02-14 09:52:00+07'),
        (se1,  5, 'BSO',  '2026-02-14 10:30:00+07',        '2026-02-14 10:35:00+07'),
        (se1,  6, 'VINH', '2026-02-14 11:30:00+07',        '2026-02-14 11:45:00+07'),
        (se1,  7, 'DHO',  '2026-02-14 15:20:00+07',        '2026-02-14 15:30:00+07'),
        (se1,  8, 'DHA',  '2026-02-14 17:05:00+07',        '2026-02-14 17:15:00+07'),
        (se1,  9, 'HUE',  '2026-02-14 18:15:00+07',        '2026-02-14 18:30:00+07'),
        (se1, 10, 'DAN',  '2026-02-14 20:10:00+07',        '2026-02-14 20:30:00+07'),
        (se1, 11, 'TAM',  '2026-02-14 21:45:00+07',        '2026-02-14 21:50:00+07'),
        (se1, 12, 'QNG',  '2026-02-14 22:45:00+07',        '2026-02-14 22:55:00+07'),
        (se1, 13, 'DIE',  '2026-02-15 01:15:00+07',        '2026-02-15 01:25:00+07'),
        (se1, 14, 'TUY',  '2026-02-15 03:20:00+07',        '2026-02-15 03:30:00+07'),
        (se1, 15, 'NTR',  '2026-02-15 06:00:00+07',        '2026-02-15 06:25:00+07'),
        (se1, 16, 'TCH',  '2026-02-15 07:30:00+07',        '2026-02-15 07:40:00+07'),
        (se1, 17, 'BTH',  '2026-02-15 09:30:00+07',        '2026-02-15 09:40:00+07'),
        (se1, 18, 'BHO',  '2026-02-15 13:30:00+07',        '2026-02-15 13:35:00+07'),
        (se1, 19, 'SG',   '2026-02-15 15:00:00+07',        NULL);

    -- SE1 carriages 1-2: SOFT_SEAT, 64 seats, no level
    FOR car_no IN 1..2 LOOP
        INSERT INTO carriage (trip_id, carriage_no, berth_class)
        VALUES (se1, car_no, 'SOFT_SEAT')
        RETURNING carriage_id INTO c_id;

        FOR b IN 1..64 LOOP
            INSERT INTO berth (trip_id, carriage_id, carriage_no, berth_no, berth_class, level, price_vnd)
            VALUES (se1, c_id, car_no, b, 'SOFT_SEAT', NULL, 258000);
        END LOOP;
    END LOOP;

    -- SE1 carriages 3-7: BERTH_6, 7 compartments × 3 levels × 2 sides = 42 berths
    FOR car_no IN 3..7 LOOP
        INSERT INTO carriage (trip_id, carriage_no, berth_class)
        VALUES (se1, car_no, 'BERTH_6')
        RETURNING carriage_id INTO c_id;

        bno := 1;
        FOR comp IN 1..7 LOOP
            FOR lvl IN 1..3 LOOP
                INSERT INTO berth (trip_id, carriage_id, carriage_no, berth_no, berth_class, level, price_vnd)
                VALUES (se1, c_id, car_no, bno, 'BERTH_6', lvl,
                        CASE lvl WHEN 1 THEN 618000 WHEN 2 THEN 598000 ELSE 578000 END);
                bno := bno + 1;
                INSERT INTO berth (trip_id, carriage_id, carriage_no, berth_no, berth_class, level, price_vnd)
                VALUES (se1, c_id, car_no, bno, 'BERTH_6', lvl,
                        CASE lvl WHEN 1 THEN 618000 WHEN 2 THEN 598000 ELSE 578000 END);
                bno := bno + 1;
            END LOOP;
        END LOOP;
    END LOOP;

    -- SE1 carriages 8-13: BERTH_4, 7 compartments × 2 levels × 2 sides = 28 berths
    FOR car_no IN 8..13 LOOP
        INSERT INTO carriage (trip_id, carriage_no, berth_class)
        VALUES (se1, car_no, 'BERTH_4')
        RETURNING carriage_id INTO c_id;

        bno := 1;
        FOR comp IN 1..7 LOOP
            FOR lvl IN 1..2 LOOP
                INSERT INTO berth (trip_id, carriage_id, carriage_no, berth_no, berth_class, level, price_vnd)
                VALUES (se1, c_id, car_no, bno, 'BERTH_4', lvl,
                        CASE lvl WHEN 1 THEN 798000 ELSE 748000 END);
                bno := bno + 1;
                INSERT INTO berth (trip_id, carriage_id, carriage_no, berth_no, berth_class, level, price_vnd)
                VALUES (se1, c_id, car_no, bno, 'BERTH_4', lvl,
                        CASE lvl WHEN 1 THEN 798000 ELSE 748000 END);
                bno := bno + 1;
            END LOOP;
        END LOOP;
    END LOOP;

    -- ── SE2  SG → HN  departs 19:00, arrives ~05:00 two days later ──────────
    INSERT INTO trip (train_code, service_date, status)
    VALUES ('SE2', '2026-02-14', 'SELLING')
    RETURNING trip_id INTO se2;

    -- Stops are the reverse geography: SG(0) … HN(19)
    INSERT INTO trip_stop (trip_id, station_index, station_code, arrives_at, departs_at) VALUES
        (se2,  0, 'SG',   NULL,                            '2026-02-14 19:00:00+07'),
        (se2,  1, 'BHO',  '2026-02-14 19:30:00+07',        '2026-02-14 19:35:00+07'),
        (se2,  2, 'BTH',  '2026-02-14 23:10:00+07',        '2026-02-14 23:20:00+07'),
        (se2,  3, 'TCH',  '2026-02-15 01:05:00+07',        '2026-02-15 01:15:00+07'),
        (se2,  4, 'NTR',  '2026-02-15 02:25:00+07',        '2026-02-15 02:50:00+07'),
        (se2,  5, 'TUY',  '2026-02-15 05:25:00+07',        '2026-02-15 05:35:00+07'),
        (se2,  6, 'DIE',  '2026-02-15 07:35:00+07',        '2026-02-15 07:45:00+07'),
        (se2,  7, 'QNG',  '2026-02-15 10:10:00+07',        '2026-02-15 10:20:00+07'),
        (se2,  8, 'TAM',  '2026-02-15 11:10:00+07',        '2026-02-15 11:15:00+07'),
        (se2,  9, 'DAN',  '2026-02-15 12:35:00+07',        '2026-02-15 13:00:00+07'),
        (se2, 10, 'HUE',  '2026-02-15 14:50:00+07',        '2026-02-15 15:05:00+07'),
        (se2, 11, 'DHA',  '2026-02-15 16:10:00+07',        '2026-02-15 16:20:00+07'),
        (se2, 12, 'DHO',  '2026-02-15 17:55:00+07',        '2026-02-15 18:05:00+07'),
        (se2, 13, 'VINH', '2026-02-15 21:50:00+07',        '2026-02-15 22:05:00+07'),
        (se2, 14, 'BSO',  '2026-02-15 23:00:00+07',        '2026-02-15 23:05:00+07'),
        (se2, 15, 'THH',  '2026-02-15 23:40:00+07',        '2026-02-15 23:47:00+07'),
        (se2, 16, 'NBI',  '2026-02-16 01:00:00+07',        '2026-02-16 01:05:00+07'),
        (se2, 17, 'NAM',  '2026-02-16 01:35:00+07',        '2026-02-16 01:40:00+07'),
        (se2, 18, 'PHU',  '2026-02-16 02:10:00+07',        '2026-02-16 02:12:00+07'),
        (se2, 19, 'HN',   '2026-02-16 05:00:00+07',        NULL);

    -- SE2 same carriage layout as SE1
    FOR car_no IN 1..2 LOOP
        INSERT INTO carriage (trip_id, carriage_no, berth_class)
        VALUES (se2, car_no, 'SOFT_SEAT')
        RETURNING carriage_id INTO c_id;
        FOR b IN 1..64 LOOP
            INSERT INTO berth (trip_id, carriage_id, carriage_no, berth_no, berth_class, level, price_vnd)
            VALUES (se2, c_id, car_no, b, 'SOFT_SEAT', NULL, 258000);
        END LOOP;
    END LOOP;

    FOR car_no IN 3..7 LOOP
        INSERT INTO carriage (trip_id, carriage_no, berth_class)
        VALUES (se2, car_no, 'BERTH_6')
        RETURNING carriage_id INTO c_id;
        bno := 1;
        FOR comp IN 1..7 LOOP
            FOR lvl IN 1..3 LOOP
                INSERT INTO berth (trip_id, carriage_id, carriage_no, berth_no, berth_class, level, price_vnd)
                VALUES (se2, c_id, car_no, bno, 'BERTH_6', lvl,
                        CASE lvl WHEN 1 THEN 618000 WHEN 2 THEN 598000 ELSE 578000 END);
                bno := bno + 1;
                INSERT INTO berth (trip_id, carriage_id, carriage_no, berth_no, berth_class, level, price_vnd)
                VALUES (se2, c_id, car_no, bno, 'BERTH_6', lvl,
                        CASE lvl WHEN 1 THEN 618000 WHEN 2 THEN 598000 ELSE 578000 END);
                bno := bno + 1;
            END LOOP;
        END LOOP;
    END LOOP;

    FOR car_no IN 8..13 LOOP
        INSERT INTO carriage (trip_id, carriage_no, berth_class)
        VALUES (se2, car_no, 'BERTH_4')
        RETURNING carriage_id INTO c_id;
        bno := 1;
        FOR comp IN 1..7 LOOP
            FOR lvl IN 1..2 LOOP
                INSERT INTO berth (trip_id, carriage_id, carriage_no, berth_no, berth_class, level, price_vnd)
                VALUES (se2, c_id, car_no, bno, 'BERTH_4', lvl,
                        CASE lvl WHEN 1 THEN 798000 ELSE 748000 END);
                bno := bno + 1;
                INSERT INTO berth (trip_id, carriage_id, carriage_no, berth_no, berth_class, level, price_vnd)
                VALUES (se2, c_id, car_no, bno, 'BERTH_4', lvl,
                        CASE lvl WHEN 1 THEN 798000 ELSE 748000 END);
                bno := bno + 1;
            END LOOP;
        END LOOP;
    END LOOP;

END;
$$;
