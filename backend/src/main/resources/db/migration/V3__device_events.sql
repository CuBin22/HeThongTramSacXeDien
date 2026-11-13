CREATE TABLE device_events (
    event_id uuid PRIMARY KEY,
    port_id uuid NOT NULL REFERENCES ports(id),
    session_id uuid,
    command_id uuid REFERENCES device_commands(id),
    type text NOT NULL,
    recorded_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    payload jsonb NOT NULL,
    FOREIGN KEY (session_id, port_id) REFERENCES charging_sessions(id, port_id)
);
CREATE INDEX device_events_port_time_idx ON device_events(port_id, recorded_at DESC);
