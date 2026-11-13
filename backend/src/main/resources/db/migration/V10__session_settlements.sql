CREATE TABLE session_settlements (
    session_id uuid PRIMARY KEY REFERENCES charging_sessions(id),
    started_at timestamptz NOT NULL,
    ended_at timestamptz NOT NULL,
    energy_kwh numeric NOT NULL CHECK (energy_kwh >= 0 AND energy_kwh < 'Infinity'::numeric),
    price_per_kwh numeric NOT NULL CHECK (price_per_kwh >= 0 AND price_per_kwh < 'Infinity'::numeric),
    energy_fee_vnd numeric NOT NULL CHECK (energy_fee_vnd >= 0 AND energy_fee_vnd < 'Infinity'::numeric),
    occupancy_fee_vnd numeric NOT NULL DEFAULT 0 CHECK (occupancy_fee_vnd = 0),
    total_fee_vnd numeric NOT NULL CHECK (total_fee_vnd = energy_fee_vnd + occupancy_fee_vnd),
    held_vnd numeric NOT NULL DEFAULT 0 CHECK (held_vnd >= 0),
    wallet_charged_vnd numeric NOT NULL DEFAULT 0 CHECK (wallet_charged_vnd >= 0 AND wallet_charged_vnd <= held_vnd),
    wallet_refunded_vnd numeric NOT NULL DEFAULT 0 CHECK (wallet_refunded_vnd = held_vnd - wallet_charged_vnd),
    amount_due_vnd numeric NOT NULL CHECK (amount_due_vnd = greatest(total_fee_vnd - wallet_charged_vnd, 0)),
    settled_at timestamptz NOT NULL DEFAULT now(),
    CHECK (ended_at >= started_at)
);

INSERT INTO session_settlements (
    session_id, started_at, ended_at, energy_kwh, price_per_kwh, energy_fee_vnd,
    occupancy_fee_vnd, total_fee_vnd, held_vnd, wallet_charged_vnd,
    wallet_refunded_vnd, amount_due_vnd, settled_at
)
SELECT cs.id, coalesce(cs.charging_started_at, cs.started_at), cs.ended_at,
       cs.energy_kwh, cs.price_per_kwh, cs.total_fee, 0, cs.total_fee,
       coalesce(ledger.held_vnd, 0), coalesce(ledger.charged_vnd, 0),
       coalesce(ledger.refunded_vnd, 0), greatest(cs.total_fee - coalesce(ledger.charged_vnd, 0), 0),
       coalesce(cs.ended_at, now())
FROM charging_sessions cs
LEFT JOIN LATERAL (
    SELECT sum(amount_vnd) FILTER (WHERE entry_type='HOLD') AS held_vnd,
           sum(amount_vnd) FILTER (WHERE entry_type='SETTLEMENT') AS charged_vnd,
           sum(amount_vnd) FILTER (WHERE entry_type='RELEASE') AS refunded_vnd
    FROM wallet_ledger WHERE session_id=cs.id
) ledger ON true
WHERE cs.status='COMPLETED'
ON CONFLICT (session_id) DO NOTHING;

CREATE OR REPLACE FUNCTION prevent_settlement_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Session settlement records are immutable' USING ERRCODE='23514';
END;
$$;
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
    owner_id uuid;
    energy_fee numeric;
    charged numeric;
    refund numeric;
    amount_due numeric;
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
    IF selected_session.energy_kwh IS NOT NULL AND p_energy_kwh<selected_session.energy_kwh THEN RAISE EXCEPTION 'Final energy cannot decrease' USING ERRCODE='22023'; END IF;

    energy_fee:=round(p_energy_kwh*selected_session.price_per_kwh,0);
    p_total_fee:=energy_fee;
    SELECT b.user_id INTO owner_id FROM bookings b WHERE b.id=selected_session.booking_id;
    started_at_value:=coalesce(selected_session.charging_started_at,selected_session.started_at);
    charged:=least(p_total_fee,selected_session.reserved_vnd);
    refund:=selected_session.reserved_vnd-charged;
    amount_due:=greatest(p_total_fee-charged,0);

    UPDATE charging_sessions SET status='COMPLETED',energy_kwh=p_energy_kwh,total_fee=p_total_fee,
        ended_at=p_ended_at,stop_command_id=p_stop_command_id,reserved_vnd=0 WHERE id=p_session_id;
    UPDATE bookings SET status='COMPLETED' WHERE id=selected_session.booking_id AND status='IN_USE';
    UPDATE ports SET status='AVAILABLE' WHERE id=selected_session.port_id AND status IN ('CHARGING','RESERVED');

    IF selected_session.reserved_vnd>0 THEN
        UPDATE wallet_accounts SET held_vnd=held_vnd-selected_session.reserved_vnd,updated_at=now() WHERE user_id=owner_id;
        UPDATE wallet_accounts SET balance_vnd=balance_vnd-charged,updated_at=now() WHERE user_id=owner_id;
        INSERT INTO wallet_ledger(user_id,session_id,entry_type,amount_vnd,idempotency_key)
        VALUES(owner_id,p_session_id,'SETTLEMENT',charged,'session:'||p_session_id||':settlement') ON CONFLICT DO NOTHING;
        IF refund>0 THEN
            INSERT INTO wallet_ledger(user_id,session_id,entry_type,amount_vnd,idempotency_key)
            VALUES(owner_id,p_session_id,'RELEASE',refund,'session:'||p_session_id||':release') ON CONFLICT DO NOTHING;
        END IF;
    END IF;

    INSERT INTO session_settlements(session_id,started_at,ended_at,energy_kwh,price_per_kwh,energy_fee_vnd,
        occupancy_fee_vnd,total_fee_vnd,held_vnd,wallet_charged_vnd,wallet_refunded_vnd,amount_due_vnd)
    VALUES(p_session_id,started_at_value,p_ended_at,p_energy_kwh,selected_session.price_per_kwh,
        energy_fee,0,p_total_fee,selected_session.reserved_vnd,charged,refund,amount_due);
END;
$$;
