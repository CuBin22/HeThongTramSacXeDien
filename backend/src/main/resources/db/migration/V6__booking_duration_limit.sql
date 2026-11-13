CREATE FUNCTION enforce_booking_duration_limit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.end_at > NEW.start_at + interval '4 hours' THEN
        RAISE EXCEPTION 'Booking duration cannot exceed 4 hours'
            USING ERRCODE = '23514', CONSTRAINT = 'bookings_duration_max_4h';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER bookings_duration_limit_insert
    BEFORE INSERT ON bookings
    FOR EACH ROW EXECUTE FUNCTION enforce_booking_duration_limit();

CREATE TRIGGER bookings_duration_limit_update
    BEFORE UPDATE OF start_at, end_at ON bookings
    FOR EACH ROW EXECUTE FUNCTION enforce_booking_duration_limit();
