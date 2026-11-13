-- VIEWS
-- Current through Flyway migration V23.

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
       p.charging_mode, p.display_name
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

CREATE OR REPLACE VIEW v_active_sessions AS
SELECT cs.id AS session_id,cs.booking_id,cs.port_id,b.user_id,b.station_id,cs.status,cs.initial_battery_percent,cs.battery_percent,cs.energy_kwh,cs.price_per_kwh,cs.started_at
FROM charging_sessions cs JOIN bookings b ON b.id=cs.booking_id WHERE cs.status<>'COMPLETED';

CREATE OR REPLACE VIEW v_session_charges AS
SELECT cs.id AS session_id,cs.booking_id,cs.port_id,b.user_id,b.station_id,cs.status,cs.energy_kwh,cs.price_per_kwh,cs.total_fee,cs.started_at,cs.ended_at,
 payment.id AS payment_id,payment.status AS payment_status
FROM charging_sessions cs JOIN bookings b ON b.id=cs.booking_id LEFT JOIN payment_transactions payment ON payment.session_id=cs.id AND payment.status='SUCCEEDED'
WHERE cs.status='COMPLETED';

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
