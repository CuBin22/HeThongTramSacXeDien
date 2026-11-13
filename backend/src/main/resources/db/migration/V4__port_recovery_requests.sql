CREATE TABLE port_recovery_requests (
    id uuid PRIMARY KEY,
    port_id uuid NOT NULL REFERENCES ports(id),
    requested_at timestamptz NOT NULL,
    acknowledged_at timestamptz CHECK (acknowledged_at >= requested_at),
    superseded_at timestamptz
);
CREATE UNIQUE INDEX port_recovery_one_pending_idx ON port_recovery_requests(port_id)
    WHERE acknowledged_at IS NULL AND superseded_at IS NULL;
