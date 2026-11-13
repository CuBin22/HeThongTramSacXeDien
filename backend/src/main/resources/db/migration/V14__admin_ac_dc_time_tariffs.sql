ALTER TABLE ports
    ADD COLUMN charging_mode text NOT NULL DEFAULT 'AC'
        CHECK (charging_mode IN ('AC', 'DC'));

ALTER TABLE tariffs
    ADD COLUMN day_price_per_kwh numeric CHECK (day_price_per_kwh > 0 AND day_price_per_kwh < 'Infinity'::numeric),
    ADD COLUMN evening_price_per_kwh numeric CHECK (evening_price_per_kwh > 0 AND evening_price_per_kwh < 'Infinity'::numeric),
    ADD COLUMN day_start time,
    ADD COLUMN day_end time,
    ADD COLUMN evening_start time,
    ADD COLUMN evening_end time,
    ADD COLUMN occupancy_grace_minutes integer NOT NULL DEFAULT 15 CHECK (occupancy_grace_minutes >= 0 AND occupancy_grace_minutes <= 1440),
    ADD COLUMN occupancy_fee_per_minute numeric NOT NULL DEFAULT 1000 CHECK (occupancy_fee_per_minute >= 0 AND occupancy_fee_per_minute < 'Infinity'::numeric),
    ADD CONSTRAINT tariff_periods_complete_ck CHECK (
        (day_price_per_kwh IS NULL AND evening_price_per_kwh IS NULL AND day_start IS NULL AND day_end IS NULL AND evening_start IS NULL AND evening_end IS NULL)
        OR
        (day_price_per_kwh IS NOT NULL AND evening_price_per_kwh IS NOT NULL AND day_start IS NOT NULL AND day_end IS NOT NULL
         AND evening_start IS NOT NULL AND evening_end IS NOT NULL AND day_start < day_end
         AND day_end = evening_start AND evening_start < evening_end)
    );

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

CREATE OR REPLACE VIEW v_port_status AS
SELECT p.id AS port_id, p.station_id, s.name AS station_name, s.active AS station_active,
       p.connector_type, p.status AS device_status,
       CASE WHEN p.status = 'AVAILABLE' AND active_session.id IS NOT NULL THEN
                CASE WHEN active_session.status = 'START_REQUESTED' THEN 'RESERVED' ELSE 'CHARGING' END
            WHEN p.status = 'AVAILABLE' AND current_booking.id IS NOT NULL THEN 'RESERVED'
            ELSE p.status END AS status,
       current_booking.id AS booking_id, active_session.id AS session_id, p.last_seen_at,
       p.max_power_kw,
       current_booking.start_at AS booking_start_at, current_booking.end_at AS booking_end_at,
       upcoming_booking.start_at AS next_booking_start_at, upcoming_booking.end_at AS next_booking_end_at,
       active_incident.description AS incident_description, active_incident.status AS incident_status,
       p.charging_mode
FROM ports p JOIN stations s ON s.id = p.station_id
LEFT JOIN charging_sessions active_session ON active_session.port_id = p.id AND active_session.status <> 'COMPLETED'
LEFT JOIN LATERAL (
    SELECT b.id,b.start_at,b.end_at FROM bookings b
    WHERE b.port_id = p.id AND b.status IN ('CONFIRMED','IN_USE') AND b.start_at <= now() AND b.end_at > now()
    ORDER BY b.start_at LIMIT 1
) current_booking ON true
LEFT JOIN LATERAL (
    SELECT b.start_at,b.end_at FROM bookings b
    WHERE b.port_id = p.id AND b.status = 'CONFIRMED' AND b.start_at > now()
    ORDER BY b.start_at LIMIT 1
) upcoming_booking ON true
LEFT JOIN LATERAL (
    SELECT i.description,i.status FROM incidents i
    WHERE i.port_id=p.id AND i.status<>'RESOLVED' ORDER BY i.reported_at DESC LIMIT 1
) active_incident ON true;
