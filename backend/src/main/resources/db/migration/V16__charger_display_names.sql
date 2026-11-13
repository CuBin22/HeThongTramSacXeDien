ALTER TABLE ports ADD COLUMN display_name text;

WITH numbered AS (
    SELECT id, charging_mode,
           row_number() OVER (PARTITION BY charging_mode ORDER BY station_id, id) AS sequence
    FROM ports
)
UPDATE ports p
SET display_name = lower(n.charging_mode) || '-' || lpad(n.sequence::text, 2, '0') || '-' ||
    CASE p.connector_type
        WHEN 'TYPE_2' THEN 'type2'
        WHEN 'GB_T' THEN 'gbt'
        WHEN 'CCS2' THEN 'ccs2'
        WHEN 'CHADEMO' THEN 'chademo'
        ELSE lower(regexp_replace(p.connector_type, '[^a-zA-Z0-9]+', '', 'g'))
    END
FROM numbered n
WHERE n.id = p.id;

ALTER TABLE ports ALTER COLUMN display_name SET NOT NULL;
ALTER TABLE ports ADD CONSTRAINT ports_display_name_nonempty_ck CHECK (btrim(display_name) <> '');
CREATE UNIQUE INDEX ports_display_name_uq ON ports(display_name);

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

CREATE TRIGGER ports_assign_display_name
BEFORE INSERT ON ports
FOR EACH ROW EXECUTE FUNCTION assign_port_display_name();

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
