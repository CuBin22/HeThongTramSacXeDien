CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email text NOT NULL UNIQUE CHECK (btrim(email) <> ''),
    password_hash text NOT NULL CHECK (btrim(password_hash) <> ''),
    role text NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'OPERATOR', 'ADMIN')),
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- The demo has one supported connector per station; no connector choice is exposed by the booking API.
CREATE TABLE stations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name text NOT NULL CHECK (btrim(name) <> ''),
    active boolean NOT NULL DEFAULT true,
    connector_type text NOT NULL DEFAULT 'STANDARD' CHECK (btrim(connector_type) <> '')
);

CREATE TABLE ports (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    station_id uuid NOT NULL REFERENCES stations(id),
    connector_type text NOT NULL DEFAULT 'STANDARD' CHECK (btrim(connector_type) <> ''),
    status text NOT NULL DEFAULT 'AVAILABLE'
        CHECK (status IN ('AVAILABLE', 'RESERVED', 'CHARGING', 'FAULTED', 'OFFLINE', 'DISABLED')),
    last_seen_at timestamptz,
    UNIQUE (id, station_id)
);
CREATE INDEX ports_station_status_idx ON ports(station_id, status, id);

CREATE TABLE tariffs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    station_id uuid REFERENCES stations(id),
    price_per_kwh numeric NOT NULL CHECK (price_per_kwh >= 0 AND price_per_kwh < 'Infinity'::numeric),
    active boolean NOT NULL DEFAULT true,
    effective_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX tariffs_station_effective_idx ON tariffs(station_id, effective_at DESC) WHERE active;

CREATE TABLE bookings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id),
    station_id uuid NOT NULL REFERENCES stations(id),
    port_id uuid NOT NULL,
    start_at timestamptz NOT NULL,
    end_at timestamptz NOT NULL CHECK (end_at > start_at),
    status text NOT NULL DEFAULT 'CONFIRMED'
        CHECK (status IN ('CONFIRMED', 'IN_USE', 'COMPLETED', 'CANCELLED', 'EXPIRED', 'UNSERVICEABLE')),
    idempotency_key text NOT NULL CHECK (btrim(idempotency_key) <> ''),
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (port_id, station_id) REFERENCES ports(id, station_id),
    UNIQUE (id, port_id),
    UNIQUE (user_id, idempotency_key),
    CONSTRAINT bookings_no_active_overlap EXCLUDE USING gist (
        port_id WITH =, tstzrange(start_at, end_at, '[)') WITH &&
    ) WHERE (status IN ('CONFIRMED', 'IN_USE'))
);
CREATE INDEX bookings_user_start_idx ON bookings(user_id, start_at DESC);

CREATE TABLE charging_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id uuid NOT NULL UNIQUE,
    port_id uuid NOT NULL,
    tariff_id uuid NOT NULL REFERENCES tariffs(id),
    -- Immutable session snapshot in VND/kWh, independent of later tariff edits.
    price_per_kwh numeric NOT NULL CHECK (price_per_kwh >= 0 AND price_per_kwh < 'Infinity'::numeric),
    initial_battery_percent integer NOT NULL CHECK (initial_battery_percent BETWEEN 0 AND 99),
    battery_percent integer NOT NULL CHECK (battery_percent BETWEEN initial_battery_percent AND 100),
    status text NOT NULL DEFAULT 'START_REQUESTED'
        CHECK (status IN ('START_REQUESTED', 'CHARGING', 'STOP_REQUESTED', 'COMPLETED')),
    energy_kwh numeric CHECK (energy_kwh >= 0 AND energy_kwh < 'Infinity'::numeric),
    total_fee numeric CHECK (total_fee >= 0 AND total_fee < 'Infinity'::numeric),
    started_at timestamptz NOT NULL DEFAULT now(),
    ended_at timestamptz CHECK (ended_at >= started_at),
    stop_command_id uuid,
    idempotency_key text NOT NULL CHECK (btrim(idempotency_key) <> ''),
    FOREIGN KEY (booking_id, port_id) REFERENCES bookings(id, port_id),
    UNIQUE (id, port_id),
    UNIQUE (booking_id, idempotency_key),
    CHECK (status <> 'COMPLETED' OR
        (energy_kwh IS NOT NULL AND total_fee IS NOT NULL AND ended_at IS NOT NULL AND stop_command_id IS NOT NULL)),
    CHECK (status = 'COMPLETED' OR (total_fee IS NULL AND ended_at IS NULL AND stop_command_id IS NULL))
);
CREATE UNIQUE INDEX sessions_one_active_per_port_idx ON charging_sessions(port_id) WHERE status <> 'COMPLETED';

CREATE TABLE device_commands (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id uuid NOT NULL,
    port_id uuid NOT NULL,
    type text NOT NULL CHECK (type IN ('START', 'STOP')),
    status text NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'SENT', 'ACKNOWLEDGED', 'FAILED', 'TIMED_OUT')),
    idempotency_key text NOT NULL UNIQUE CHECK (btrim(idempotency_key) <> ''),
    created_at timestamptz NOT NULL DEFAULT now(),
    sent_at timestamptz,
    acknowledged_at timestamptz,
    reason text,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    FOREIGN KEY (session_id, port_id) REFERENCES charging_sessions(id, port_id),
    UNIQUE (id, session_id),
    CHECK (status <> 'ACKNOWLEDGED' OR acknowledged_at IS NOT NULL)
);
CREATE INDEX device_commands_delivery_idx ON device_commands(status, created_at) WHERE status IN ('PENDING', 'SENT', 'TIMED_OUT');
ALTER TABLE charging_sessions ADD CONSTRAINT session_stop_command_fk
    FOREIGN KEY (stop_command_id, id) REFERENCES device_commands(id, session_id);

CREATE TABLE telemetry (
    event_id uuid PRIMARY KEY,
    session_id uuid NOT NULL,
    port_id uuid NOT NULL,
    battery_percent integer NOT NULL CHECK (battery_percent BETWEEN 0 AND 100),
    energy_kwh numeric NOT NULL CHECK (energy_kwh >= 0 AND energy_kwh < 'Infinity'::numeric),
    recorded_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    schema_version integer NOT NULL DEFAULT 1 CHECK (schema_version > 0),
    FOREIGN KEY (session_id, port_id) REFERENCES charging_sessions(id, port_id)
);
CREATE INDEX telemetry_session_time_idx ON telemetry(session_id, recorded_at DESC);

CREATE TABLE incidents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    port_id uuid NOT NULL REFERENCES ports(id),
    session_id uuid,
    description text NOT NULL CHECK (btrim(description) <> ''),
    status text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    reported_at timestamptz NOT NULL DEFAULT now(),
    acknowledged_by uuid REFERENCES users(id),
    resolved_by uuid REFERENCES users(id),
    resolved_at timestamptz,
    FOREIGN KEY (session_id, port_id) REFERENCES charging_sessions(id, port_id),
    CHECK (status <> 'RESOLVED' OR (resolved_at IS NOT NULL AND resolved_by IS NOT NULL))
);
CREATE INDEX incidents_open_port_idx ON incidents(port_id, reported_at) WHERE status <> 'RESOLVED';

CREATE TABLE payment_transactions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id uuid NOT NULL REFERENCES charging_sessions(id),
    transaction_reference text NOT NULL UNIQUE CHECK (btrim(transaction_reference) <> ''),
    provider_transaction_id text UNIQUE,
    amount_vnd numeric NOT NULL CHECK (amount_vnd >= 0 AND amount_vnd < 'Infinity'::numeric),
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    idempotency_key text NOT NULL CHECK (btrim(idempotency_key) <> ''),
    created_at timestamptz NOT NULL DEFAULT now(),
    paid_at timestamptz,
    UNIQUE (session_id, idempotency_key)
);
CREATE UNIQUE INDEX payments_one_success_per_session_idx ON payment_transactions(session_id) WHERE status = 'SUCCEEDED';

CREATE TABLE status_audit (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entity_table text NOT NULL,
    entity_id uuid NOT NULL,
    old_status text NOT NULL,
    new_status text NOT NULL,
    changed_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX status_audit_entity_idx ON status_audit(entity_table, entity_id, changed_at);
