-- TRIGGERS
-- Current through Flyway migration V23.

CREATE TRIGGER ports_status_audit AFTER UPDATE OF status ON ports
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status) EXECUTE FUNCTION audit_status_change();

CREATE TRIGGER bookings_status_audit AFTER UPDATE OF status ON bookings
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status) EXECUTE FUNCTION audit_status_change();

CREATE TRIGGER sessions_status_audit AFTER UPDATE OF status ON charging_sessions
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status) EXECUTE FUNCTION audit_status_change();

CREATE TRIGGER commands_status_audit AFTER UPDATE OF status ON device_commands
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status) EXECUTE FUNCTION audit_status_change();

CREATE TRIGGER incidents_status_audit AFTER UPDATE OF status ON incidents
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status) EXECUTE FUNCTION audit_status_change();

CREATE TRIGGER payments_status_audit AFTER UPDATE OF status ON payment_transactions
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status) EXECUTE FUNCTION audit_status_change();

CREATE TRIGGER sessions_invariants BEFORE UPDATE ON charging_sessions
    FOR EACH ROW EXECUTE FUNCTION guard_session_invariants();

CREATE TRIGGER bookings_duration_limit_insert
    BEFORE INSERT ON bookings
    FOR EACH ROW EXECUTE FUNCTION enforce_booking_duration_limit();

CREATE TRIGGER bookings_duration_limit_update
    BEFORE UPDATE OF start_at, end_at ON bookings
    FOR EACH ROW EXECUTE FUNCTION enforce_booking_duration_limit();

CREATE TRIGGER session_settlements_immutable
BEFORE UPDATE OR DELETE ON session_settlements
FOR EACH ROW EXECUTE FUNCTION prevent_settlement_mutation();

CREATE TRIGGER ports_assign_display_name
BEFORE INSERT ON ports
FOR EACH ROW EXECUTE FUNCTION assign_port_display_name();
