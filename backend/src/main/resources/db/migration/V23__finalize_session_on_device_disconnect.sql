ALTER TABLE charging_sessions
    ADD COLUMN completion_reason text;

UPDATE charging_sessions SET completion_reason = 'DEVICE_ACK' WHERE status = 'COMPLETED';

ALTER TABLE charging_sessions ADD CONSTRAINT charging_sessions_completion_reason_ck CHECK (
        (status = 'COMPLETED' AND completion_reason IN ('DEVICE_ACK', 'DEVICE_DISCONNECTED')) OR
        (status <> 'COMPLETED' AND completion_reason IS NULL)
    );

CREATE OR REPLACE FUNCTION guard_session_invariants() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.booking_id,NEW.port_id,NEW.tariff_id,NEW.price_per_kwh,NEW.initial_battery_percent,NEW.vehicle_id,
           NEW.battery_capacity_kwh,NEW.target_type,NEW.target_battery_percent,NEW.target_budget_vnd)
       IS DISTINCT FROM ROW(OLD.booking_id,OLD.port_id,OLD.tariff_id,OLD.price_per_kwh,OLD.initial_battery_percent,OLD.vehicle_id,
           OLD.battery_capacity_kwh,OLD.target_type,OLD.target_battery_percent,OLD.target_budget_vnd) THEN
        RAISE EXCEPTION 'Session allocation, vehicle, battery capacity, target and tariff snapshot are immutable' USING ERRCODE='23514';
    END IF;
    IF OLD.status='COMPLETED' AND ROW(NEW.status,NEW.energy_kwh,NEW.total_fee,NEW.ended_at,NEW.stop_command_id,NEW.completion_reason)
       IS DISTINCT FROM ROW(OLD.status,OLD.energy_kwh,OLD.total_fee,OLD.ended_at,OLD.stop_command_id,OLD.completion_reason) THEN
        RAISE EXCEPTION 'Completed session charge is immutable' USING ERRCODE='23514';
    END IF;
    IF NEW.battery_percent<OLD.battery_percent OR (OLD.energy_kwh IS NOT NULL AND (NEW.energy_kwh IS NULL OR NEW.energy_kwh<OLD.energy_kwh)) THEN
        RAISE EXCEPTION 'Session telemetry cannot decrease' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;

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
    -- Do not make the port available. Admin must resolve the incident and confirm recovery.
    INSERT INTO session_settlements(session_id,started_at,ended_at,energy_kwh,price_per_kwh,energy_fee_vnd,
        occupancy_fee_vnd,total_fee_vnd,amount_due_vnd)
    VALUES(p_session_id,started_at_value,p_detected_at,last_energy,selected_session.price_per_kwh,
        energy_fee,0,p_total_fee,p_total_fee);
END;
$$;
