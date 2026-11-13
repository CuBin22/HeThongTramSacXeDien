CREATE TABLE vehicles (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name text NOT NULL CHECK (btrim(name) <> ''),
    model text NOT NULL CHECK (btrim(model) <> ''),
    connector_type text NOT NULL CHECK (btrim(connector_type) <> ''),
    battery_capacity_kwh numeric NOT NULL CHECK (battery_capacity_kwh > 0 AND battery_capacity_kwh < 'Infinity'::numeric),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id,user_id)
);

CREATE TABLE wallet_accounts (
    user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    balance_vnd numeric NOT NULL DEFAULT 0 CHECK (balance_vnd >= 0 AND balance_vnd < 'Infinity'::numeric),
    held_vnd numeric NOT NULL DEFAULT 0 CHECK (held_vnd >= 0 AND held_vnd <= balance_vnd AND held_vnd < 'Infinity'::numeric),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE wallet_ledger (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id),
    session_id uuid REFERENCES charging_sessions(id),
    entry_type text NOT NULL CHECK (entry_type IN ('DEMO_CREDIT','HOLD','SETTLEMENT','RELEASE')),
    amount_vnd numeric NOT NULL CHECK (amount_vnd >= 0 AND amount_vnd < 'Infinity'::numeric),
    idempotency_key text NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE charging_sessions
    ADD COLUMN vehicle_id uuid,
    ADD COLUMN target_type text NOT NULL DEFAULT 'FULL' CHECK (target_type IN ('FULL','BATTERY_PERCENT','BUDGET')),
    ADD COLUMN target_battery_percent integer,
    ADD COLUMN target_budget_vnd numeric,
    ADD COLUMN reserved_vnd numeric NOT NULL DEFAULT 0 CHECK (reserved_vnd >= 0 AND reserved_vnd < 'Infinity'::numeric),
    ADD CONSTRAINT sessions_vehicle_owner_fk FOREIGN KEY (vehicle_id) REFERENCES vehicles(id),
    ADD CONSTRAINT sessions_target_values_ck CHECK (
        (target_type='FULL' AND target_battery_percent IS NULL AND target_budget_vnd IS NULL) OR
        (target_type='BATTERY_PERCENT' AND target_battery_percent BETWEEN 1 AND 100 AND target_budget_vnd IS NULL) OR
        (target_type='BUDGET' AND target_budget_vnd > 0 AND target_budget_vnd < 'Infinity'::numeric AND target_battery_percent IS NULL)
    );

CREATE VIEW v_user_wallet AS
SELECT user_id,balance_vnd,held_vnd,balance_vnd-held_vnd AS available_vnd,updated_at FROM wallet_accounts;

CREATE OR REPLACE PROCEDURE complete_session(
    IN p_session_id uuid, IN p_stop_command_id uuid, IN p_energy_kwh numeric,
    IN p_ended_at timestamptz, OUT p_total_fee numeric
)
LANGUAGE plpgsql AS $$
DECLARE
    selected_session charging_sessions%ROWTYPE;
    owner_id uuid;
    charged numeric;
    refund numeric;
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
    IF selected_session.energy_kwh IS NOT NULL AND p_energy_kwh<selected_session.energy_kwh THEN RAISE EXCEPTION 'Final energy cannot decrease' USING ERRCODE='22023'; END IF;
    p_total_fee:=p_energy_kwh*selected_session.price_per_kwh;
    UPDATE charging_sessions SET status='COMPLETED',energy_kwh=p_energy_kwh,total_fee=p_total_fee,ended_at=p_ended_at,stop_command_id=p_stop_command_id WHERE id=p_session_id;
    UPDATE bookings SET status='COMPLETED' WHERE id=selected_session.booking_id AND status='IN_USE';
    UPDATE ports SET status='AVAILABLE' WHERE id=selected_session.port_id AND status IN ('CHARGING','RESERVED');
    SELECT b.user_id INTO owner_id FROM bookings b WHERE b.id=selected_session.booking_id;
    IF selected_session.reserved_vnd>0 THEN
        charged:=least(p_total_fee,selected_session.reserved_vnd);
        refund:=selected_session.reserved_vnd-charged;
        UPDATE wallet_accounts SET held_vnd=held_vnd-selected_session.reserved_vnd,updated_at=now() WHERE user_id=owner_id;
        UPDATE wallet_accounts SET balance_vnd=balance_vnd-charged,updated_at=now() WHERE user_id=owner_id;
        INSERT INTO wallet_ledger(user_id,session_id,entry_type,amount_vnd,idempotency_key)
        VALUES(owner_id,p_session_id,'SETTLEMENT',charged,'session:'||p_session_id||':settlement') ON CONFLICT DO NOTHING;
        IF refund>0 THEN INSERT INTO wallet_ledger(user_id,session_id,entry_type,amount_vnd,idempotency_key)
          VALUES(owner_id,p_session_id,'RELEASE',refund,'session:'||p_session_id||':release') ON CONFLICT DO NOTHING; END IF;
        UPDATE charging_sessions SET reserved_vnd=0 WHERE id=p_session_id;
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION initialize_wallet() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN INSERT INTO wallet_accounts(user_id,balance_vnd) VALUES(NEW.id,500000) ON CONFLICT DO NOTHING; RETURN NEW; END; $$;
CREATE TRIGGER users_initialize_wallet AFTER INSERT ON users FOR EACH ROW EXECUTE FUNCTION initialize_wallet();
INSERT INTO wallet_accounts(user_id,balance_vnd) SELECT id,500000 FROM users ON CONFLICT DO NOTHING;

CREATE OR REPLACE FUNCTION guard_session_invariants() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.booking_id,NEW.port_id,NEW.tariff_id,NEW.price_per_kwh,NEW.initial_battery_percent,NEW.vehicle_id,NEW.target_type,NEW.target_battery_percent,NEW.target_budget_vnd)
       IS DISTINCT FROM ROW(OLD.booking_id,OLD.port_id,OLD.tariff_id,OLD.price_per_kwh,OLD.initial_battery_percent,OLD.vehicle_id,OLD.target_type,OLD.target_battery_percent,OLD.target_budget_vnd) THEN
        RAISE EXCEPTION 'Session allocation, vehicle, target and tariff snapshot are immutable' USING ERRCODE='23514';
    END IF;
    IF OLD.status='COMPLETED' AND ROW(NEW.status,NEW.energy_kwh,NEW.total_fee,NEW.ended_at,NEW.stop_command_id,NEW.reserved_vnd)
       IS DISTINCT FROM ROW(OLD.status,OLD.energy_kwh,OLD.total_fee,OLD.ended_at,OLD.stop_command_id,OLD.reserved_vnd) THEN RAISE EXCEPTION 'Completed session charge is immutable' USING ERRCODE='23514'; END IF;
    IF NEW.battery_percent<OLD.battery_percent OR (OLD.energy_kwh IS NOT NULL AND (NEW.energy_kwh IS NULL OR NEW.energy_kwh<OLD.energy_kwh)) THEN RAISE EXCEPTION 'Session telemetry cannot decrease' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END; $$;

CREATE OR REPLACE VIEW v_active_sessions AS
SELECT cs.id AS session_id,cs.booking_id,cs.port_id,b.user_id,b.station_id,cs.status,cs.initial_battery_percent,cs.battery_percent,cs.energy_kwh,cs.price_per_kwh,cs.started_at
FROM charging_sessions cs JOIN bookings b ON b.id=cs.booking_id WHERE cs.status<>'COMPLETED';
CREATE OR REPLACE VIEW v_session_charges AS
SELECT cs.id AS session_id,cs.booking_id,cs.port_id,b.user_id,b.station_id,cs.status,cs.energy_kwh,cs.price_per_kwh,cs.total_fee,cs.started_at,cs.ended_at,
 payment.id AS payment_id,payment.status AS payment_status
FROM charging_sessions cs JOIN bookings b ON b.id=cs.booking_id LEFT JOIN payment_transactions payment ON payment.session_id=cs.id AND payment.status='SUCCEEDED'
WHERE cs.status='COMPLETED';
