-- Stage-2 Experiment C: seed 48 additional SE1 trips (2026-02-15 .. 2026-04-03).
-- Each trip is an identical copy of SE1 2026-02-14 (same carriages, same berths,
-- timestamps shifted by N days). Together with the original this gives 49
-- independent contention domains — enough to show the USL partition effect.

DO $$
DECLARE
    base_id  BIGINT;
    new_id   BIGINT;
    c_id     BIGINT;
    bno      SMALLINT;
BEGIN
    SELECT trip_id INTO base_id
    FROM trip WHERE train_code = 'SE1' AND service_date = '2026-02-14';

    FOR day_offset IN 1..48 LOOP

        INSERT INTO trip (train_code, service_date, status)
        VALUES ('SE1', DATE '2026-02-14' + day_offset, 'SELLING')
        RETURNING trip_id INTO new_id;

        -- Stops: copy from base trip, shift timestamps by day_offset days.
        -- NULL arrives_at (origin) and NULL departs_at (terminus) stay NULL.
        INSERT INTO trip_stop (trip_id, station_index, station_code, arrives_at, departs_at)
        SELECT new_id,
               station_index,
               station_code,
               arrives_at  + (day_offset || ' days')::INTERVAL,
               departs_at  + (day_offset || ' days')::INTERVAL
        FROM trip_stop
        WHERE trip_id = base_id;

        -- Carriages 1-2: SOFT_SEAT, 64 seats each
        FOR car_no IN 1..2 LOOP
            INSERT INTO carriage (trip_id, carriage_no, berth_class)
            VALUES (new_id, car_no, 'SOFT_SEAT')
            RETURNING carriage_id INTO c_id;

            FOR b IN 1..64 LOOP
                INSERT INTO berth (trip_id, carriage_id, carriage_no, berth_no, berth_class, level, price_vnd)
                VALUES (new_id, c_id, car_no, b, 'SOFT_SEAT', NULL, 258000);
            END LOOP;
        END LOOP;

        -- Carriages 3-7: BERTH_6, 7 compartments × 3 levels × 2 sides = 42 berths
        FOR car_no IN 3..7 LOOP
            INSERT INTO carriage (trip_id, carriage_no, berth_class)
            VALUES (new_id, car_no, 'BERTH_6')
            RETURNING carriage_id INTO c_id;

            bno := 1;
            FOR comp IN 1..7 LOOP
                FOR lvl IN 1..3 LOOP
                    INSERT INTO berth (trip_id, carriage_id, carriage_no, berth_no, berth_class, level, price_vnd)
                    VALUES (new_id, c_id, car_no, bno, 'BERTH_6', lvl,
                            CASE lvl WHEN 1 THEN 618000 WHEN 2 THEN 598000 ELSE 578000 END);
                    bno := bno + 1;
                    INSERT INTO berth (trip_id, carriage_id, carriage_no, berth_no, berth_class, level, price_vnd)
                    VALUES (new_id, c_id, car_no, bno, 'BERTH_6', lvl,
                            CASE lvl WHEN 1 THEN 618000 WHEN 2 THEN 598000 ELSE 578000 END);
                    bno := bno + 1;
                END LOOP;
            END LOOP;
        END LOOP;

        -- Carriages 8-13: BERTH_4, 7 compartments × 2 levels × 2 sides = 28 berths
        FOR car_no IN 8..13 LOOP
            INSERT INTO carriage (trip_id, carriage_no, berth_class)
            VALUES (new_id, car_no, 'BERTH_4')
            RETURNING carriage_id INTO c_id;

            bno := 1;
            FOR comp IN 1..7 LOOP
                FOR lvl IN 1..2 LOOP
                    INSERT INTO berth (trip_id, carriage_id, carriage_no, berth_no, berth_class, level, price_vnd)
                    VALUES (new_id, c_id, car_no, bno, 'BERTH_4', lvl,
                            CASE lvl WHEN 1 THEN 798000 ELSE 748000 END);
                    bno := bno + 1;
                    INSERT INTO berth (trip_id, carriage_id, carriage_no, berth_no, berth_class, level, price_vnd)
                    VALUES (new_id, c_id, car_no, bno, 'BERTH_4', lvl,
                            CASE lvl WHEN 1 THEN 798000 ELSE 748000 END);
                    bno := bno + 1;
                END LOOP;
            END LOOP;
        END LOOP;

    END LOOP;
END;
$$;
