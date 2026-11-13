ALTER TABLE charging_sessions
    ADD COLUMN charging_started_at timestamptz;

COMMENT ON COLUMN charging_sessions.charging_started_at IS
    'Device-confirmed charging start time; distinct from the session request time.';

UPDATE charging_sessions cs
SET charging_started_at = (
    SELECT dc.acknowledged_at
    FROM device_commands dc
    WHERE dc.session_id = cs.id
      AND dc.type = 'START'
      AND dc.status = 'ACKNOWLEDGED'
      AND dc.acknowledged_at IS NOT NULL
    ORDER BY dc.acknowledged_at
    LIMIT 1
)
WHERE EXISTS (
    SELECT 1
    FROM device_commands dc
    WHERE dc.session_id = cs.id
      AND dc.type = 'START'
      AND dc.status = 'ACKNOWLEDGED'
      AND dc.acknowledged_at IS NOT NULL
);

ALTER TABLE charging_sessions
    ADD CONSTRAINT charging_started_at_after_request
    CHECK (charging_started_at IS NULL OR charging_started_at >= started_at);
