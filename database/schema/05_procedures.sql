-- STORED PROCEDURES
-- Current through Flyway migration V23.

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

    -- A missed arrival window must not keep a port blocked, even if the scheduler is between runs.
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

CREATE OR REPLACE PROCEDURE complete_session(
    IN p_session_id uuid, IN p_stop_command_id uuid, IN p_energy_kwh numeric,
    IN p_ended_at timestamptz, OUT p_total_fee numeric
)
LANGUAGE plpgsql AS $$
DECLARE
    selected_session charging_sessions%ROWTYPE;
    energy_fee numeric;
    started_at_value timestamptz;
BEGIN
    SELECT s.* INTO selected_session FROM charging_sessions s WHERE s.id=p_session_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Session does not exist' USING ERRCODE='P0001'; END IF;
    PERFORM 1 FROM device_commands c WHERE c.id=p_stop_command_id AND c.session_id=p_session_id
      AND c.port_id=selected_session.port_id AND c.type='STOP' AND c.status='ACKNOWLEDGED' AND c.acknowledged_at IS NOT NULL FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Matching persisted acknowledged STOP command is required' USING ERRCODE='22023'; END IF;
    IF selected_session.status='COMPLETED' THEN p_total_fee:=selected_session.total_fee; RETURN; END IF;
    IF selected_session.status<>'STOP_REQUESTED' OR p_energy_kwh IS NULL OR p_energy_kwh<0 OR p_energy_kwh>='Infinity'::numeric
       OR p_ended_at IS NULL OR p_ended_at<selected_session.started_at THEN
        RAISE EXCEPTION 'Valid stopped session and final energy are required' USING ERRCODE='22023';
    END IF;
    IF selected_session.energy_kwh IS NOT NULL AND p_energy_kwh<selected_session.energy_kwh THEN
        RAISE EXCEPTION 'Final energy cannot decrease' USING ERRCODE='22023';
    END IF;

    energy_fee:=round(p_energy_kwh*selected_session.price_per_kwh,0);
    p_total_fee:=energy_fee;
    started_at_value:=coalesce(selected_session.charging_started_at,selected_session.started_at);
    UPDATE charging_sessions SET status='COMPLETED',energy_kwh=p_energy_kwh,total_fee=p_total_fee,
        ended_at=p_ended_at,stop_command_id=p_stop_command_id,completion_reason='DEVICE_ACK' WHERE id=p_session_id;
    UPDATE bookings SET status='COMPLETED' WHERE id=selected_session.booking_id AND status='IN_USE';
    UPDATE ports SET status='AVAILABLE' WHERE id=selected_session.port_id AND status IN ('CHARGING','RESERVED');
    INSERT INTO session_settlements(session_id,started_at,ended_at,energy_kwh,price_per_kwh,energy_fee_vnd,
        occupancy_fee_vnd,total_fee_vnd,amount_due_vnd)
    VALUES(p_session_id,started_at_value,p_ended_at,p_energy_kwh,selected_session.price_per_kwh,
        energy_fee,0,p_total_fee,p_total_fee);
END;
$$;

CREATE OR REPLACE PROCEDURE complete_disconnected_session(
    IN p_session_id uuid, IN p_stop_command_id uuid, IN p_detected_at timestamptz, OUT p_total_fee numeric
)
LANGUAGE plpgsql AS $$
DECLARE
    selected_session charging_sessions%ROWTYPE;
    last_energy numeric;
    energy_fee numeric;
    started_at_value timestamptz;
BEGIN
    SELECT s.* INTO selected_session FROM charging_sessions s WHERE s.id=p_session_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Session does not exist' USING ERRCODE='P0001'; END IF;
    IF selected_session.status='COMPLETED' THEN p_total_fee:=selected_session.total_fee; RETURN; END IF;
    IF selected_session.status<>'STOP_REQUESTED' OR p_detected_at IS NULL OR p_detected_at<selected_session.started_at
       OR NOT EXISTS (SELECT 1 FROM ports p WHERE p.id=selected_session.port_id AND p.status='OFFLINE') THEN
        RAISE EXCEPTION 'A disconnected port and a pending stop request are required' USING ERRCODE='22023';
    END IF;
    PERFORM 1 FROM device_commands c WHERE c.id=p_stop_command_id AND c.session_id=p_session_id
      AND c.port_id=selected_session.port_id AND c.type='STOP' AND c.status<>'ACKNOWLEDGED' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Matching unacknowledged STOP command is required' USING ERRCODE='22023'; END IF;

    last_energy:=coalesce(selected_session.energy_kwh,0);
    energy_fee:=round(last_energy*selected_session.price_per_kwh,0);
    p_total_fee:=energy_fee;
    started_at_value:=coalesce(selected_session.charging_started_at,selected_session.started_at);
    UPDATE device_commands SET status='TIMED_OUT' WHERE id=p_stop_command_id AND status<>'ACKNOWLEDGED';
    UPDATE charging_sessions SET status='COMPLETED',energy_kwh=last_energy,total_fee=p_total_fee,
        ended_at=p_detected_at,stop_command_id=p_stop_command_id,completion_reason='DEVICE_DISCONNECTED' WHERE id=p_session_id;
    UPDATE bookings SET status='COMPLETED' WHERE id=selected_session.booking_id AND status='IN_USE';
    -- Keep the port unavailable until an administrator confirms the device recovery.
    INSERT INTO session_settlements(session_id,started_at,ended_at,energy_kwh,price_per_kwh,energy_fee_vnd,
        occupancy_fee_vnd,total_fee_vnd,amount_due_vnd)
    VALUES(p_session_id,started_at_value,p_detected_at,last_energy,selected_session.price_per_kwh,
        energy_fee,0,p_total_fee,p_total_fee);
END;
$$;
