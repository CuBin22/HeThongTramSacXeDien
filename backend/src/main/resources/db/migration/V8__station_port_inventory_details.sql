ALTER TABLE ports
    ADD COLUMN max_power_kw numeric NOT NULL DEFAULT 3.0
        CHECK (max_power_kw > 0 AND max_power_kw <= 100 AND max_power_kw < 'Infinity'::numeric);
COMMENT ON COLUMN ports.max_power_kw IS 'Demo-configured maximum charging power in kW; editable by an administrator.';

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
       active_incident.description AS incident_description, active_incident.status AS incident_status
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
