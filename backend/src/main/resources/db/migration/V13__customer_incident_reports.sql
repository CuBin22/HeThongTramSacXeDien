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
