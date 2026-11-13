DROP PROCEDURE reserve_port(uuid, uuid, uuid, uuid, timestamptz, timestamptz, text);

CREATE OR REPLACE FUNCTION enforce_booking_duration_limit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.end_at <> NEW.start_at + interval '4 hours' THEN
        RAISE EXCEPTION 'Bookings must reserve a four-hour interval'
            USING ERRCODE = '23514', CONSTRAINT = 'bookings_duration_max_4h';
    END IF;
    RETURN NEW;
END;
$$;

CREATE PROCEDURE reserve_port(
    IN p_user_id uuid,
    IN p_station_id uuid,
    IN p_port_id uuid,
    IN p_vehicle_id uuid,
    IN p_start_at timestamptz,
    IN p_end_at timestamptz,
    IN p_idempotency_key text,
    OUT p_booking_id uuid,
    OUT p_reserved_port_id uuid
)
LANGUAGE plpgsql AS $$
DECLARE
    existing bookings%ROWTYPE;
    selected_station stations%ROWTYPE;
    selected_port ports%ROWTYPE;
    vehicle_connector text;
BEGIN
    IF p_user_id IS NULL OR p_station_id IS NULL OR p_port_id IS NULL OR p_vehicle_id IS NULL
       OR p_start_at IS NULL OR p_end_at IS NULL OR p_idempotency_key IS NULL OR btrim(p_idempotency_key) = '' THEN
        RAISE EXCEPTION 'User, station, port, vehicle, interval and idempotency key are required' USING ERRCODE = '22023';
    END IF;
    IF p_end_at <> p_start_at + interval '4 hours' THEN
        RAISE EXCEPTION 'Reservations must hold the port for exactly four hours' USING ERRCODE = '22023';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended(p_user_id::text || ':' || p_idempotency_key, 0));
    SELECT b.* INTO existing FROM bookings b WHERE b.user_id = p_user_id AND b.idempotency_key = p_idempotency_key;
    IF FOUND THEN
        IF existing.station_id <> p_station_id OR existing.port_id <> p_port_id
           OR existing.vehicle_id IS DISTINCT FROM p_vehicle_id
           OR existing.start_at <> p_start_at OR existing.end_at <> p_end_at THEN
            RAISE EXCEPTION 'Idempotency key was already used for another reservation' USING ERRCODE = '22023';
        END IF;
        p_booking_id := existing.id;
        p_reserved_port_id := existing.port_id;
        RETURN;
    END IF;
    IF p_start_at <= now() THEN
        RAISE EXCEPTION 'Arrival time must be in the future' USING ERRCODE = '22023';
    END IF;

    SELECT s.* INTO selected_station FROM stations s WHERE s.id = p_station_id FOR UPDATE;
    IF NOT FOUND OR NOT selected_station.active THEN
        RAISE EXCEPTION 'Station is missing or inactive' USING ERRCODE = 'P0001';
    END IF;

    -- Release missed reservations even if the expiry scheduler is between runs.
    UPDATE bookings b SET status = 'EXPIRED'
    WHERE b.port_id IN (SELECT p.id FROM ports p WHERE p.station_id = p_station_id)
      AND b.status = 'CONFIRMED' AND b.start_at + interval '15 minutes' <= now()
      AND NOT EXISTS (SELECT 1 FROM charging_sessions cs WHERE cs.booking_id = b.id);

    SELECT p.* INTO selected_port FROM ports p
    WHERE p.id = p_port_id AND p.station_id = p_station_id FOR UPDATE;
    IF NOT FOUND OR selected_port.status <> 'AVAILABLE'
       OR EXISTS (SELECT 1 FROM charging_sessions cs WHERE cs.port_id = p_port_id AND cs.status <> 'COMPLETED') THEN
        RAISE EXCEPTION 'Selected port is unavailable' USING ERRCODE = 'P0001';
    END IF;

    SELECT v.connector_type INTO vehicle_connector FROM vehicles v
    WHERE v.id = p_vehicle_id AND v.user_id = p_user_id FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Saved vehicle does not belong to this user' USING ERRCODE = '22023';
    END IF;
    IF vehicle_connector <> selected_port.connector_type THEN
        RAISE EXCEPTION 'Selected port is incompatible with the vehicle' USING ERRCODE = '22023';
    END IF;

    IF EXISTS (SELECT 1 FROM bookings b WHERE b.port_id = p_port_id
        AND b.status IN ('CONFIRMED', 'IN_USE')
        AND tstzrange(b.start_at, b.end_at, '[)') && tstzrange(p_start_at, p_end_at, '[)')) THEN
        RAISE EXCEPTION 'Selected port is already reserved for this time' USING ERRCODE = 'P0001';
    END IF;

    INSERT INTO bookings(user_id, station_id, port_id, vehicle_id, start_at, end_at, idempotency_key)
    VALUES (p_user_id, p_station_id, p_port_id, p_vehicle_id, p_start_at, p_end_at, p_idempotency_key)
    RETURNING id, port_id INTO p_booking_id, p_reserved_port_id;
END;
$$;
