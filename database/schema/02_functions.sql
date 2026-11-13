-- SQL FUNCTIONS AND TRIGGER FUNCTIONS
-- Current through Flyway migration V23.

CREATE FUNCTION audit_status_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO status_audit(entity_table, entity_id, old_status, new_status)
    VALUES (TG_TABLE_NAME, NEW.id, OLD.status, NEW.status);
    RETURN NEW;
END;
$$;

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

CREATE OR REPLACE FUNCTION enforce_booking_duration_limit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.end_at <> NEW.start_at + interval '4 hours' THEN
        RAISE EXCEPTION 'Bookings must reserve a four-hour interval'
            USING ERRCODE = '23514', CONSTRAINT = 'bookings_duration_max_4h';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION prevent_settlement_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Session settlement records are immutable' USING ERRCODE='23514';
END;
$$;

CREATE OR REPLACE FUNCTION tariff_price_at(p_tariff_id uuid, p_at timestamptz)
RETURNS numeric LANGUAGE sql STABLE AS $$
    SELECT CASE
        WHEN t.day_price_per_kwh IS NULL THEN t.price_per_kwh
        WHEN (p_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::time >= t.day_start
         AND (p_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::time < t.day_end THEN t.day_price_per_kwh
        WHEN (p_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::time >= t.evening_start
         AND (p_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::time < t.evening_end THEN t.evening_price_per_kwh
        ELSE t.evening_price_per_kwh
    END
    FROM tariffs t WHERE t.id=p_tariff_id
$$;

CREATE FUNCTION assign_port_display_name() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    next_number bigint;
    connector_slug text;
BEGIN
    IF NEW.display_name IS NOT NULL AND btrim(NEW.display_name) <> '' THEN
        RETURN NEW;
    END IF;

    PERFORM pg_advisory_xact_lock(20261028, 1);
    SELECT count(*) + 1 INTO next_number FROM ports WHERE charging_mode = NEW.charging_mode;
    connector_slug := CASE NEW.connector_type
        WHEN 'TYPE_2' THEN 'type2'
        WHEN 'GB_T' THEN 'gbt'
        WHEN 'CCS2' THEN 'ccs2'
        WHEN 'CHADEMO' THEN 'chademo'
        ELSE 'standard'
    END;
    NEW.display_name := lower(NEW.charging_mode) || '-' || lpad(next_number::text, 2, '0') || '-' || connector_slug;
    RETURN NEW;
END;
$$;
