ALTER TABLE charging_sessions ADD COLUMN battery_capacity_kwh numeric;

UPDATE charging_sessions cs
SET battery_capacity_kwh=coalesce(v.battery_capacity_kwh,3)
FROM vehicles v
WHERE v.id=cs.vehicle_id;

UPDATE charging_sessions SET battery_capacity_kwh=3 WHERE battery_capacity_kwh IS NULL;

ALTER TABLE charging_sessions
    ALTER COLUMN battery_capacity_kwh SET NOT NULL,
    ALTER COLUMN battery_capacity_kwh SET DEFAULT 3,
    ADD CONSTRAINT sessions_battery_capacity_ck CHECK (battery_capacity_kwh > 0 AND battery_capacity_kwh < 'Infinity'::numeric);

CREATE OR REPLACE FUNCTION guard_session_invariants() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.booking_id,NEW.port_id,NEW.tariff_id,NEW.price_per_kwh,NEW.initial_battery_percent,NEW.vehicle_id,
           NEW.battery_capacity_kwh,NEW.target_type,NEW.target_battery_percent,NEW.target_budget_vnd)
       IS DISTINCT FROM ROW(OLD.booking_id,OLD.port_id,OLD.tariff_id,OLD.price_per_kwh,OLD.initial_battery_percent,OLD.vehicle_id,
           OLD.battery_capacity_kwh,OLD.target_type,OLD.target_battery_percent,OLD.target_budget_vnd) THEN
        RAISE EXCEPTION 'Session allocation, vehicle, battery capacity, target and tariff snapshot are immutable' USING ERRCODE='23514';
    END IF;
    IF OLD.status='COMPLETED' AND ROW(NEW.status,NEW.energy_kwh,NEW.total_fee,NEW.ended_at,NEW.stop_command_id)
       IS DISTINCT FROM ROW(OLD.status,OLD.energy_kwh,OLD.total_fee,OLD.ended_at,OLD.stop_command_id) THEN
        RAISE EXCEPTION 'Completed session charge is immutable' USING ERRCODE='23514';
    END IF;
    IF NEW.battery_percent<OLD.battery_percent OR (OLD.energy_kwh IS NOT NULL AND (NEW.energy_kwh IS NULL OR NEW.energy_kwh<OLD.energy_kwh)) THEN
        RAISE EXCEPTION 'Session telemetry cannot decrease' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
