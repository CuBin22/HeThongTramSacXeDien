-- Retire the separate operator role and move incident handling to the admin workflow.
ALTER TABLE users DROP CONSTRAINT users_role_check;
UPDATE users SET role = 'USER' WHERE role = 'OPERATOR';
ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('USER', 'ADMIN'));

ALTER TABLE incidents DROP CONSTRAINT incidents_status_check;
ALTER TABLE incidents
    ADD COLUMN assigned_staff_name text,
    ADD COLUMN assignment_note text,
    ADD COLUMN assigned_at timestamptz,
    ADD COLUMN repair_report text,
    ADD COLUMN repair_reported_at timestamptz,
    ADD COLUMN repair_reported_by uuid REFERENCES users(id);

-- Preserve work already acknowledged under the previous workflow and make it visible for reassignment.
UPDATE incidents
SET status = 'ASSIGNED',
    assigned_staff_name = 'Chưa ghi nhận - cần xác nhận lại',
    assigned_at = reported_at
WHERE status = 'ACKNOWLEDGED';

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
