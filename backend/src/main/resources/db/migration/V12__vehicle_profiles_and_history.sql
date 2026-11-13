ALTER TABLE vehicles
    ADD COLUMN license_plate text,
    ADD COLUMN production_year integer,
    ADD COLUMN max_charging_power_kw numeric,
    ADD COLUMN is_default boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT vehicles_license_plate_ck CHECK (license_plate IS NULL OR btrim(license_plate) <> ''),
    ADD CONSTRAINT vehicles_production_year_ck CHECK (production_year IS NULL OR production_year BETWEEN 1900 AND 2100),
    ADD CONSTRAINT vehicles_max_power_ck CHECK (max_charging_power_kw IS NULL OR (max_charging_power_kw > 0 AND max_charging_power_kw < 'Infinity'::numeric));

WITH first_vehicle AS (
    SELECT id, row_number() OVER (PARTITION BY user_id ORDER BY created_at, id) AS position
    FROM vehicles
)
UPDATE vehicles v SET is_default = true
FROM first_vehicle f WHERE f.id = v.id AND f.position = 1;

CREATE UNIQUE INDEX vehicles_one_default_per_user_idx ON vehicles(user_id) WHERE is_default;
CREATE INDEX sessions_vehicle_completed_idx ON charging_sessions(vehicle_id, ended_at DESC)
    WHERE status = 'COMPLETED' AND vehicle_id IS NOT NULL;

CREATE OR REPLACE VIEW v_user_vehicle_charging_history AS
SELECT b.user_id,
       cs.id AS session_id,
       cs.vehicle_id,
       v.name AS vehicle_name,
       b.station_id,
       st.name AS station_name,
       cs.port_id,
       coalesce(ss.started_at, cs.started_at) AS started_at,
       coalesce(ss.ended_at, cs.ended_at) AS ended_at,
       coalesce(ss.energy_kwh, cs.energy_kwh) AS energy_kwh,
       coalesce(ss.total_fee_vnd, cs.total_fee, 0) AS total_cost_vnd,
       coalesce(ss.wallet_charged_vnd, 0) AS wallet_charged_vnd,
       coalesce(pay.external_paid_vnd, 0) AS external_paid_vnd,
       coalesce(ss.wallet_charged_vnd, 0) + coalesce(pay.external_paid_vnd, 0) AS amount_paid_vnd
FROM charging_sessions cs
JOIN bookings b ON b.id = cs.booking_id
JOIN vehicles v ON v.id = cs.vehicle_id AND v.user_id = b.user_id
JOIN stations st ON st.id = b.station_id
LEFT JOIN session_settlements ss ON ss.session_id = cs.id
LEFT JOIN LATERAL (
    SELECT sum(pt.amount_vnd) AS external_paid_vnd
    FROM payment_transactions pt
    WHERE pt.session_id = cs.id AND pt.status = 'SUCCEEDED'
) pay ON true
WHERE cs.status = 'COMPLETED' AND cs.vehicle_id IS NOT NULL;
