-- TABLES, CONSTRAINTS, INDEXES AND EXTENSION
-- Current through Flyway migration V23.

CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email text NOT NULL UNIQUE CHECK (btrim(email) <> ''),
    password_hash text NOT NULL CHECK (btrim(password_hash) <> ''),
    role text NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'OPERATOR', 'ADMIN')),
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);

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
    completion_reason text,
    idempotency_key text NOT NULL CHECK (btrim(idempotency_key) <> ''),
    FOREIGN KEY (booking_id, port_id) REFERENCES bookings(id, port_id),
    UNIQUE (id, port_id),
    UNIQUE (booking_id, idempotency_key),
    CHECK (status <> 'COMPLETED' OR
        (energy_kwh IS NOT NULL AND total_fee IS NOT NULL AND ended_at IS NOT NULL AND stop_command_id IS NOT NULL
         AND completion_reason IN ('DEVICE_ACK','DEVICE_DISCONNECTED'))),
    CHECK (status = 'COMPLETED' OR (total_fee IS NULL AND ended_at IS NULL AND stop_command_id IS NULL AND completion_reason IS NULL))
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

CREATE TABLE port_recovery_requests (
    id uuid PRIMARY KEY,
    port_id uuid NOT NULL REFERENCES ports(id),
    requested_at timestamptz NOT NULL,
    acknowledged_at timestamptz CHECK (acknowledged_at >= requested_at),
    superseded_at timestamptz
);

CREATE UNIQUE INDEX port_recovery_one_pending_idx ON port_recovery_requests(port_id)
    WHERE acknowledged_at IS NULL AND superseded_at IS NULL;

ALTER TABLE payment_transactions ADD COLUMN redirect_url text;

CREATE TABLE vehicles (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name text NOT NULL CHECK (btrim(name) <> ''),
    model text NOT NULL CHECK (btrim(model) <> ''),
    connector_type text NOT NULL CHECK (btrim(connector_type) <> ''),
    battery_capacity_kwh numeric NOT NULL CHECK (battery_capacity_kwh > 0 AND battery_capacity_kwh < 'Infinity'::numeric),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id,user_id)
);

ALTER TABLE charging_sessions
    ADD COLUMN vehicle_id uuid,
    ADD COLUMN target_type text NOT NULL DEFAULT 'FULL' CHECK (target_type IN ('FULL','BATTERY_PERCENT','BUDGET')),
    ADD COLUMN target_battery_percent integer,
    ADD COLUMN target_budget_vnd numeric,
    ADD COLUMN reserved_vnd numeric NOT NULL DEFAULT 0 CHECK (reserved_vnd >= 0 AND reserved_vnd < 'Infinity'::numeric),
    ADD CONSTRAINT sessions_vehicle_owner_fk FOREIGN KEY (vehicle_id) REFERENCES vehicles(id),
    ADD CONSTRAINT sessions_target_values_ck CHECK (
        (target_type='FULL' AND target_battery_percent IS NULL AND target_budget_vnd IS NULL) OR
        (target_type='BATTERY_PERCENT' AND target_battery_percent BETWEEN 1 AND 100 AND target_budget_vnd IS NULL) OR
        (target_type='BUDGET' AND target_budget_vnd > 0 AND target_budget_vnd < 'Infinity'::numeric AND target_battery_percent IS NULL)
    );

ALTER TABLE ports
    ADD COLUMN max_power_kw numeric NOT NULL DEFAULT 3.0
        CHECK (max_power_kw > 0 AND max_power_kw <= 100 AND max_power_kw < 'Infinity'::numeric);

ALTER TABLE charging_sessions
    ADD COLUMN charging_started_at timestamptz;

ALTER TABLE charging_sessions
    ADD CONSTRAINT charging_started_at_after_request
    CHECK (charging_started_at IS NULL OR charging_started_at >= started_at);

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

ALTER TABLE bookings ADD COLUMN vehicle_id uuid REFERENCES vehicles(id);

ALTER TABLE vehicles
    ADD COLUMN license_plate text,
    ADD COLUMN production_year integer,
    ADD COLUMN max_charging_power_kw numeric,
    ADD COLUMN is_default boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT vehicles_license_plate_ck CHECK (license_plate IS NULL OR btrim(license_plate) <> ''),
    ADD CONSTRAINT vehicles_production_year_ck CHECK (production_year IS NULL OR production_year BETWEEN 1900 AND 2100),
    ADD CONSTRAINT vehicles_max_power_ck CHECK (max_charging_power_kw IS NULL OR (max_charging_power_kw > 0 AND max_charging_power_kw < 'Infinity'::numeric));

CREATE UNIQUE INDEX vehicles_one_default_per_user_idx ON vehicles(user_id) WHERE is_default;

CREATE INDEX sessions_vehicle_completed_idx ON charging_sessions(vehicle_id, ended_at DESC)
    WHERE status = 'COMPLETED' AND vehicle_id IS NOT NULL;

ALTER TABLE incidents
    ADD COLUMN report_type text NOT NULL DEFAULT 'DEVICE'
        CHECK (report_type IN ('DEVICE', 'CUSTOMER')),
    ADD COLUMN category text,
    ADD COLUMN reporter_user_id uuid REFERENCES users(id),
    ADD COLUMN idempotency_key text;

ALTER TABLE incidents ADD CONSTRAINT customer_incident_metadata_ck CHECK (
    (report_type = 'DEVICE' AND reporter_user_id IS NULL AND idempotency_key IS NULL)
    OR
    (report_type = 'CUSTOMER' AND reporter_user_id IS NOT NULL AND idempotency_key IS NOT NULL
        AND category IN ('START_CHARGE_FAILED','GUN_STUCK','INCORRECT_BILLING','DISPLAY_FAILURE','STOP_CHARGE_FAILED','OTHER'))
);

CREATE UNIQUE INDEX incidents_reporter_idempotency_idx
    ON incidents(reporter_user_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE INDEX incidents_reporter_time_idx ON incidents(reporter_user_id, reported_at DESC)
    WHERE reporter_user_id IS NOT NULL;

CREATE TABLE incident_attachments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id uuid NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    file_name text NOT NULL CHECK (btrim(file_name) <> ''),
    content_type text NOT NULL CHECK (content_type IN ('image/jpeg','image/png','image/webp')),
    file_data bytea NOT NULL,
    uploaded_at timestamptz NOT NULL DEFAULT now(),
    CHECK (octet_length(file_data) BETWEEN 1 AND 5242880)
);

CREATE INDEX incident_attachments_incident_idx ON incident_attachments(incident_id, uploaded_at, id);

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

ALTER TABLE ports ADD COLUMN display_name text;

ALTER TABLE ports ALTER COLUMN display_name SET NOT NULL;

ALTER TABLE ports ADD CONSTRAINT ports_display_name_nonempty_ck CHECK (btrim(display_name) <> '');

CREATE UNIQUE INDEX ports_display_name_uq ON ports(display_name);

ALTER TABLE ports DROP CONSTRAINT ports_max_power_kw_check;

ALTER TABLE ports
    ADD CONSTRAINT ports_mode_power_range_ck CHECK (
        (charging_mode = 'AC' AND max_power_kw BETWEEN 10 AND 22)
        OR (charging_mode = 'DC' AND max_power_kw BETWEEN 30 AND 150)
    );

ALTER TABLE tariffs
    ADD CONSTRAINT tariffs_hospital_wide_ck CHECK (station_id IS NULL);

ALTER TABLE users DROP CONSTRAINT users_role_check;

ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('USER', 'ADMIN'));

ALTER TABLE incidents DROP CONSTRAINT incidents_status_check;

ALTER TABLE incidents
    ADD COLUMN assigned_staff_name text,
    ADD COLUMN assignment_note text,
    ADD COLUMN assigned_at timestamptz,
    ADD COLUMN repair_report text,
    ADD COLUMN repair_reported_at timestamptz,
    ADD COLUMN repair_reported_by uuid REFERENCES users(id);

ALTER TABLE incidents
    ADD CONSTRAINT incidents_status_check CHECK (status IN ('OPEN', 'ASSIGNED', 'REPAIR_REPORTED', 'RESOLVED')),
    ADD CONSTRAINT incidents_assignment_required_ck CHECK (
        status NOT IN ('ASSIGNED', 'REPAIR_REPORTED') OR
        (assigned_staff_name IS NOT NULL AND btrim(assigned_staff_name) <> '' AND assigned_at IS NOT NULL)
    ),
    ADD CONSTRAINT incidents_repair_report_required_ck CHECK (
        status <> 'REPAIR_REPORTED' OR
        (repair_report IS NOT NULL AND btrim(repair_report) <> '' AND repair_reported_at IS NOT NULL AND repair_reported_by IS NOT NULL)
    ),
    ADD CONSTRAINT incidents_assigned_staff_length_ck CHECK (assigned_staff_name IS NULL OR length(assigned_staff_name) <= 120),
    ADD CONSTRAINT incidents_assignment_note_length_ck CHECK (assignment_note IS NULL OR length(assignment_note) <= 1000),
    ADD CONSTRAINT incidents_repair_report_length_ck CHECK (repair_report IS NULL OR length(repair_report) <= 2000);

CREATE INDEX incidents_work_queue_idx ON incidents(status, reported_at DESC)
    WHERE status <> 'RESOLVED';

ALTER TABLE charging_sessions DROP COLUMN reserved_vnd;

ALTER TABLE session_settlements DROP COLUMN held_vnd,DROP COLUMN wallet_charged_vnd,DROP COLUMN wallet_refunded_vnd;

ALTER TABLE session_settlements ADD CONSTRAINT session_settlements_amount_due_ck CHECK (amount_due_vnd=total_fee_vnd);

ALTER TABLE charging_sessions ADD COLUMN battery_capacity_kwh numeric;

ALTER TABLE charging_sessions
    ALTER COLUMN battery_capacity_kwh SET NOT NULL,
    ALTER COLUMN battery_capacity_kwh SET DEFAULT 3,
    ADD CONSTRAINT sessions_battery_capacity_ck CHECK (battery_capacity_kwh > 0 AND battery_capacity_kwh < 'Infinity'::numeric);
