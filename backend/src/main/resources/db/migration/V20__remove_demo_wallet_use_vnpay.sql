-- Wallet balances were seeded with demo money. Charging is now paid in full through VNPay.
CREATE OR REPLACE FUNCTION guard_session_invariants() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.booking_id,NEW.port_id,NEW.tariff_id,NEW.price_per_kwh,NEW.initial_battery_percent,NEW.vehicle_id,NEW.target_type,NEW.target_battery_percent,NEW.target_budget_vnd)
       IS DISTINCT FROM ROW(OLD.booking_id,OLD.port_id,OLD.tariff_id,OLD.price_per_kwh,OLD.initial_battery_percent,OLD.vehicle_id,OLD.target_type,OLD.target_battery_percent,OLD.target_budget_vnd) THEN
        RAISE EXCEPTION 'Session allocation, vehicle, target and tariff snapshot are immutable' USING ERRCODE='23514';
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

-- Recalculate historic demo-wallet settlements as fully payable amounts.
DROP TRIGGER session_settlements_immutable ON session_settlements;
UPDATE session_settlements
SET held_vnd=0,wallet_charged_vnd=0,wallet_refunded_vnd=0,amount_due_vnd=total_fee_vnd;
UPDATE charging_sessions SET reserved_vnd=0;
UPDATE wallet_accounts SET balance_vnd=0,held_vnd=0,updated_at=now();

DROP VIEW v_user_vehicle_charging_history;
DROP VIEW v_user_wallet;
DROP TRIGGER users_initialize_wallet ON users;
DROP FUNCTION initialize_wallet();
DROP TABLE wallet_ledger;
DROP TABLE wallet_accounts;
ALTER TABLE charging_sessions DROP COLUMN reserved_vnd;
ALTER TABLE session_settlements DROP COLUMN held_vnd,DROP COLUMN wallet_charged_vnd,DROP COLUMN wallet_refunded_vnd;
ALTER TABLE session_settlements ADD CONSTRAINT session_settlements_amount_due_ck CHECK (amount_due_vnd=total_fee_vnd);

CREATE TRIGGER session_settlements_immutable
BEFORE UPDATE OR DELETE ON session_settlements
FOR EACH ROW EXECUTE FUNCTION prevent_settlement_mutation();

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
        ended_at=p_ended_at,stop_command_id=p_stop_command_id WHERE id=p_session_id;
    UPDATE bookings SET status='COMPLETED' WHERE id=selected_session.booking_id AND status='IN_USE';
    UPDATE ports SET status='AVAILABLE' WHERE id=selected_session.port_id AND status IN ('CHARGING','RESERVED');
    INSERT INTO session_settlements(session_id,started_at,ended_at,energy_kwh,price_per_kwh,energy_fee_vnd,
        occupancy_fee_vnd,total_fee_vnd,amount_due_vnd)
    VALUES(p_session_id,started_at_value,p_ended_at,p_energy_kwh,selected_session.price_per_kwh,
        energy_fee,0,p_total_fee,p_total_fee);
END;
$$;

CREATE OR REPLACE VIEW v_user_vehicle_charging_history AS
SELECT b.user_id,cs.id AS session_id,cs.vehicle_id,v.name AS vehicle_name,b.station_id,st.name AS station_name,
       cs.port_id,coalesce(ss.started_at,cs.started_at) AS started_at,coalesce(ss.ended_at,cs.ended_at) AS ended_at,
       coalesce(ss.energy_kwh,cs.energy_kwh) AS energy_kwh,coalesce(ss.total_fee_vnd,cs.total_fee,0) AS total_cost_vnd,
       coalesce(pay.paid_vnd,0) AS external_paid_vnd,coalesce(pay.paid_vnd,0) AS amount_paid_vnd
FROM charging_sessions cs
JOIN bookings b ON b.id=cs.booking_id
JOIN vehicles v ON v.id=cs.vehicle_id AND v.user_id=b.user_id
JOIN stations st ON st.id=b.station_id
LEFT JOIN session_settlements ss ON ss.session_id=cs.id
LEFT JOIN LATERAL (SELECT sum(pt.amount_vnd) AS paid_vnd FROM payment_transactions pt
                   WHERE pt.session_id=cs.id AND pt.status='SUCCEEDED') pay ON true
WHERE cs.status='COMPLETED' AND cs.vehicle_id IS NOT NULL;
