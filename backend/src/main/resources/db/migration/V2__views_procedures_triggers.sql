CREATE PROCEDURE reserve_port(
    IN p_user_id uuid, IN p_station_id uuid, IN p_start_at timestamptz,
    IN p_end_at timestamptz, IN p_idempotency_key text,
    OUT p_booking_id uuid, OUT p_port_id uuid
)
LANGUAGE plpgsql AS $$
DECLARE
    existing bookings%ROWTYPE;
    selected_station stations%ROWTYPE;
BEGIN
    IF p_user_id IS NULL OR p_station_id IS NULL OR p_start_at IS NULL OR p_end_at IS NULL
       OR p_end_at <= p_start_at OR p_idempotency_key IS NULL OR btrim(p_idempotency_key) = '' THEN
        RAISE EXCEPTION 'Valid user, station, interval and idempotency key are required' USING ERRCODE = '22023';
    END IF;

    -- Serializes retries even when a reused key names a different station.
    PERFORM pg_advisory_xact_lock(hashtextextended(p_user_id::text || ':' || p_idempotency_key, 0));
    SELECT b.* INTO existing FROM bookings b
        WHERE b.user_id = p_user_id AND b.idempotency_key = p_idempotency_key;
    IF FOUND THEN
        IF existing.station_id <> p_station_id OR existing.start_at <> p_start_at OR existing.end_at <> p_end_at THEN
            RAISE EXCEPTION 'Idempotency key was already used for another reservation' USING ERRCODE = '22023';
        END IF;
        p_booking_id := existing.id;
        p_port_id := existing.port_id;
        RETURN;
    END IF;

    SELECT s.* INTO selected_station FROM stations s WHERE s.id = p_station_id FOR UPDATE;
    IF NOT FOUND OR NOT selected_station.active THEN
        RAISE EXCEPTION 'Station is missing or inactive' USING ERRCODE = 'P0001';
    END IF;

    SELECT p.id INTO p_port_id FROM ports p
    WHERE p.station_id = p_station_id AND p.status = 'AVAILABLE'
      AND p.connector_type = selected_station.connector_type
      AND NOT EXISTS (
          SELECT 1 FROM bookings b
          WHERE b.port_id = p.id AND b.status IN ('CONFIRMED', 'IN_USE')
            AND tstzrange(b.start_at, b.end_at, '[)') && tstzrange(p_start_at, p_end_at, '[)')
      )
    ORDER BY p.id LIMIT 1 FOR UPDATE OF p;
    IF p_port_id IS NULL THEN
        RAISE EXCEPTION 'No eligible port for requested interval' USING ERRCODE = 'P0001';
    END IF;

    INSERT INTO bookings(user_id, station_id, port_id, start_at, end_at, idempotency_key)
    VALUES (p_user_id, p_station_id, p_port_id, p_start_at, p_end_at, p_idempotency_key)
    RETURNING id INTO p_booking_id;
END;
$$;

CREATE PROCEDURE complete_session(
    IN p_session_id uuid, IN p_stop_command_id uuid, IN p_energy_kwh numeric,
    IN p_ended_at timestamptz, OUT p_total_fee numeric
)
LANGUAGE plpgsql AS $$
DECLARE
    selected_session charging_sessions%ROWTYPE;
BEGIN
    SELECT s.* INTO selected_session FROM charging_sessions s WHERE s.id = p_session_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Session does not exist' USING ERRCODE = 'P0001';
    END IF;

    -- A STOP on another session (including an earlier session on the same port) is never evidence of a stop here.
    PERFORM 1 FROM device_commands c
    WHERE c.id = p_stop_command_id AND c.session_id = p_session_id
      AND c.port_id = selected_session.port_id AND c.type = 'STOP'
      AND c.status = 'ACKNOWLEDGED' AND c.acknowledged_at IS NOT NULL
    FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Matching persisted acknowledged STOP command is required' USING ERRCODE = '22023';
    END IF;

    IF selected_session.status = 'COMPLETED' THEN
        p_total_fee := selected_session.total_fee;
        RETURN;
    END IF;
    IF selected_session.status <> 'STOP_REQUESTED' THEN
        RAISE EXCEPTION 'Session must be waiting for stop acknowledgement' USING ERRCODE = '22023';
    END IF;
    IF p_energy_kwh IS NULL OR NOT (p_energy_kwh >= 0 AND p_energy_kwh < 'Infinity'::numeric)
       OR p_ended_at IS NULL OR p_ended_at < selected_session.started_at THEN
        RAISE EXCEPTION 'Valid final energy and end time are required' USING ERRCODE = '22023';
    END IF;
    IF selected_session.energy_kwh IS NOT NULL AND p_energy_kwh < selected_session.energy_kwh THEN
        RAISE EXCEPTION 'Final energy cannot decrease' USING ERRCODE = '22023';
    END IF;

    p_total_fee := p_energy_kwh * selected_session.price_per_kwh;
    UPDATE charging_sessions SET status = 'COMPLETED', energy_kwh = p_energy_kwh,
        total_fee = p_total_fee, ended_at = p_ended_at, stop_command_id = p_stop_command_id
    WHERE id = p_session_id;
    UPDATE bookings SET status = 'COMPLETED'
        WHERE id = selected_session.booking_id AND status = 'IN_USE';
    -- Fault, offline and maintenance states survive financial/session finalization.
    UPDATE ports SET status = 'AVAILABLE'
        WHERE id = selected_session.port_id AND status IN ('CHARGING', 'RESERVED');
END;
$$;

CREATE VIEW v_port_status AS
SELECT p.id AS port_id, p.station_id, s.name AS station_name, s.active AS station_active,
       p.connector_type, p.status AS device_status,
       CASE WHEN p.status = 'AVAILABLE' AND active_session.id IS NOT NULL THEN
                CASE WHEN active_session.status = 'START_REQUESTED' THEN 'RESERVED' ELSE 'CHARGING' END
            WHEN p.status = 'AVAILABLE' AND current_booking.id IS NOT NULL THEN 'RESERVED'
            ELSE p.status END AS status,
       current_booking.id AS booking_id, active_session.id AS session_id, p.last_seen_at
FROM ports p JOIN stations s ON s.id = p.station_id
LEFT JOIN charging_sessions active_session ON active_session.port_id = p.id AND active_session.status <> 'COMPLETED'
LEFT JOIN LATERAL (
    SELECT b.id FROM bookings b WHERE b.port_id = p.id AND b.status IN ('CONFIRMED', 'IN_USE')
        AND b.start_at <= now() AND b.end_at > now()
    ORDER BY b.start_at LIMIT 1
) current_booking ON true;

CREATE VIEW v_active_sessions AS
SELECT cs.id AS session_id, cs.booking_id, cs.port_id, b.user_id, b.station_id,
       cs.status, cs.initial_battery_percent, cs.battery_percent,
       cs.energy_kwh, cs.price_per_kwh, cs.started_at
FROM charging_sessions cs JOIN bookings b ON b.id = cs.booking_id
WHERE cs.status <> 'COMPLETED';

CREATE VIEW v_session_charges AS
SELECT cs.id AS session_id, cs.booking_id, cs.port_id, b.user_id, b.station_id,
       cs.status, cs.energy_kwh, cs.price_per_kwh, cs.total_fee, cs.started_at, cs.ended_at,
       payment.id AS payment_id, payment.status AS payment_status
FROM charging_sessions cs JOIN bookings b ON b.id = cs.booking_id
LEFT JOIN payment_transactions payment ON payment.session_id = cs.id AND payment.status = 'SUCCEEDED'
WHERE cs.status = 'COMPLETED';

CREATE FUNCTION audit_status_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO status_audit(entity_table, entity_id, old_status, new_status)
    VALUES (TG_TABLE_NAME, NEW.id, OLD.status, NEW.status);
    RETURN NEW;
END;
$$;

CREATE TRIGGER ports_status_audit AFTER UPDATE OF status ON ports
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status) EXECUTE FUNCTION audit_status_change();
CREATE TRIGGER bookings_status_audit AFTER UPDATE OF status ON bookings
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status) EXECUTE FUNCTION audit_status_change();
CREATE TRIGGER sessions_status_audit AFTER UPDATE OF status ON charging_sessions
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status) EXECUTE FUNCTION audit_status_change();
CREATE TRIGGER commands_status_audit AFTER UPDATE OF status ON device_commands
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status) EXECUTE FUNCTION audit_status_change();
CREATE TRIGGER incidents_status_audit AFTER UPDATE OF status ON incidents
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status) EXECUTE FUNCTION audit_status_change();
CREATE TRIGGER payments_status_audit AFTER UPDATE OF status ON payment_transactions
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status) EXECUTE FUNCTION audit_status_change();

CREATE FUNCTION guard_session_invariants() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.booking_id, NEW.port_id, NEW.tariff_id, NEW.price_per_kwh, NEW.initial_battery_percent)
       IS DISTINCT FROM ROW(OLD.booking_id, OLD.port_id, OLD.tariff_id, OLD.price_per_kwh, OLD.initial_battery_percent) THEN
        RAISE EXCEPTION 'Session allocation and tariff snapshot are immutable' USING ERRCODE = '23514';
    END IF;
    IF OLD.status = 'COMPLETED' AND
       ROW(NEW.status, NEW.energy_kwh, NEW.total_fee, NEW.ended_at, NEW.stop_command_id)
       IS DISTINCT FROM ROW(OLD.status, OLD.energy_kwh, OLD.total_fee, OLD.ended_at, OLD.stop_command_id) THEN
        RAISE EXCEPTION 'Completed session charge is immutable' USING ERRCODE = '23514';
    END IF;
    IF NEW.battery_percent < OLD.battery_percent OR
       (OLD.energy_kwh IS NOT NULL AND (NEW.energy_kwh IS NULL OR NEW.energy_kwh < OLD.energy_kwh)) THEN
        RAISE EXCEPTION 'Session telemetry cannot decrease' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER sessions_invariants BEFORE UPDATE ON charging_sessions
    FOR EACH ROW EXECUTE FUNCTION guard_session_invariants();
