package vn.edu.campuscharge.db;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;

/** Real PostgreSQL contract tests; DB_TEST_URL may target a disposable local database. */
class DatabaseObjectsIT {
    static PostgreSQLContainer<?> postgres;
    static String url;
    static String username;
    static String password;
    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID STATION = UUID.fromString("00000000-0000-0000-0000-000000000010");
    static final UUID PORT = UUID.fromString("00000000-0000-0000-0000-000000000020");
    static final UUID SECOND_PORT = UUID.fromString("00000000-0000-0000-0000-000000000021");
    static final UUID TARIFF = UUID.fromString("00000000-0000-0000-0000-000000000030");
    static final OffsetDateTime START = OffsetDateTime.parse("2030-01-01T10:00:00Z");
    static final OffsetDateTime END = START.plusHours(1);

    @BeforeAll static void migrate() {
        url = System.getenv("DB_TEST_URL");
        if (url == null || url.isBlank()) {
            postgres = new PostgreSQLContainer<>("postgres:16-alpine");
            postgres.start();
            url = postgres.getJdbcUrl();
            username = postgres.getUsername();
            password = postgres.getPassword();
        } else {
            username = System.getenv().getOrDefault("DB_TEST_USERNAME", "campus_test");
            password = System.getenv().getOrDefault("DB_TEST_PASSWORD", "");
        }
        Flyway.configure().dataSource(url, username, password).load().migrate();
    }

    @AfterAll static void stop() { if (postgres != null) postgres.stop(); }

    @BeforeEach void seed() throws SQLException {
        execute("TRUNCATE users, stations, tariffs, status_audit RESTART IDENTITY CASCADE");
        execute("INSERT INTO users(id,email,password_hash,role) VALUES (?,?,?,'USER')", USER, "user@campus.test", "test-hash");
        execute("INSERT INTO stations(id,name,active) VALUES (?, 'Campus', true)", STATION);
        execute("INSERT INTO ports(id,station_id,connector_type,status) VALUES (?,?,'STANDARD','AVAILABLE')", PORT, STATION);
        execute("INSERT INTO tariffs(id,price_per_kwh) VALUES (?,3000)", TARIFF);
    }

    @Test void migrationsExposeRequiredTablesViewsAndAuditOnlyRealStatusChanges() throws Exception {
        for (String table : List.of("users", "stations", "ports", "tariffs", "bookings", "charging_sessions",
                "device_commands", "telemetry", "incidents", "payment_transactions", "status_audit")) {
            assertThat(scalar("SELECT to_regclass(?)::text", table)).isEqualTo(table);
        }
        execute("UPDATE ports SET status='FAULTED' WHERE id=?", PORT);
        execute("UPDATE ports SET status='FAULTED' WHERE id=?", PORT);
        assertThat(scalar("SELECT status FROM v_port_status WHERE port_id=?", PORT)).isEqualTo("FAULTED");
        assertThat(scalar("SELECT count(*) FROM status_audit WHERE entity_id=? AND old_status='AVAILABLE' AND new_status='FAULTED'", PORT)).isEqualTo(1L);
        assertThat(scalar("SELECT count(*) FROM v_active_sessions")).isEqualTo(0L);
        assertThat(scalar("SELECT count(*) FROM v_session_charges")).isEqualTo(0L);
    }

    @Test void allocatesLowestEligiblePortAndReplaysWithoutAnotherBooking() throws Exception {
        execute("INSERT INTO ports(id,station_id,connector_type,status) VALUES (?,?,'STANDARD','AVAILABLE')", SECOND_PORT, STATION);
        UUID booking = reserve("first", START, END).bookingId();
        assertThat(reserve("first", START, END)).isEqualTo(new Reservation(booking, PORT));
        assertThat(reserve("second", START, END).portId()).isEqualTo(SECOND_PORT);
        assertThat(scalar("SELECT count(*) FROM bookings")).isEqualTo(2L);
        assertThatThrownBy(() -> reserve("first", START.plusHours(2), END.plusHours(2))).isInstanceOf(SQLException.class);
    }

    @Test void halfOpenIntervalsAllowAdjacentBookingsButRetainInUseConflicts() throws Exception {
        UUID booking = reserve("first", START, END).bookingId();
        execute("UPDATE bookings SET status='IN_USE' WHERE id=?", booking);
        assertThat(reserve("adjacent", END, END.plusHours(1)).portId()).isEqualTo(PORT);
        assertThatThrownBy(() -> reserve("overlap", START.plusMinutes(30), END)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> reserve("empty", START, START)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> reserve("reversed", END, START)).isInstanceOf(SQLException.class);
    }

    @Test void exclusionConstraintProtectsDirectWritesAndCancelledBookingsReleaseTheInterval() throws Exception {
        UUID booking = reserve("first", START, END).bookingId();
        assertThatThrownBy(() -> insertBooking(UUID.randomUUID(), "direct", START.plusMinutes(1), END))
                .isInstanceOf(SQLException.class).extracting(e -> ((SQLException)e).getSQLState()).isEqualTo("23P01");
        execute("UPDATE bookings SET status='CANCELLED' WHERE id=?", booking);
        assertThat(reserve("replacement", START, END).portId()).isEqualTo(PORT);
    }

    @Test void inactiveStationsAndIncompatibleOrUnavailablePortsCannotAllocate() throws Exception {
        execute("UPDATE stations SET active=false WHERE id=?", STATION);
        assertThatThrownBy(() -> reserve("inactive", START, END)).isInstanceOf(SQLException.class);
        execute("UPDATE stations SET active=true WHERE id=?", STATION);
        for (String status : List.of("RESERVED", "CHARGING", "FAULTED", "OFFLINE", "DISABLED")) {
            execute("UPDATE ports SET status=? WHERE id=?", status, PORT);
            assertThatThrownBy(() -> reserve(status, START, END)).isInstanceOf(SQLException.class);
        }
        execute("UPDATE ports SET status='AVAILABLE', connector_type='OTHER' WHERE id=?", PORT);
        assertThatThrownBy(() -> reserve("incompatible", START, END)).isInstanceOf(SQLException.class);
    }

    @Test void simultaneousReservationsForLastPortHaveExactlyOneWinner() throws Exception {
        var outcomes = race(false);
        assertThat(outcomes.stream().filter(Reservation.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(SQLException.class::isInstance)).hasSize(1);
        assertThat(scalar("SELECT count(*) FROM bookings")).isEqualTo(1L);
    }

    @Test void simultaneousIdempotencyRetriesReturnTheSameReservation() throws Exception {
        var outcomes = race(true);
        assertThat(outcomes.get(0)).isInstanceOf(Reservation.class).isEqualTo(outcomes.get(1));
        assertThat(scalar("SELECT count(*) FROM bookings")).isEqualTo(1L);
    }

    @Test void completesOnlyAfterMatchingPersistedStopAcknowledgementAndFreezesSnapshotFee() throws Exception {
        UUID session = session("main");
        UUID stop = command(session, "STOP", "SENT");
        assertThatThrownBy(() -> complete(session, stop, "1.25")).isInstanceOf(SQLException.class);
        assertThat(scalar("SELECT status FROM charging_sessions WHERE id=?", session)).isEqualTo("STOP_REQUESTED");
        execute("UPDATE device_commands SET status='ACKNOWLEDGED', acknowledged_at=? WHERE id=?", END, stop);
        execute("UPDATE tariffs SET price_per_kwh=9999 WHERE id=?", TARIFF);
        assertThat(complete(session, stop, "1.25")).isEqualByComparingTo("3750");
        assertThat(complete(session, stop, "9.99")).isEqualByComparingTo("3750");
        assertThat(scalar("SELECT energy_kwh FROM charging_sessions WHERE id=?", session)).isEqualTo(new BigDecimal("1.25"));
        assertThat(scalar("SELECT status FROM bookings WHERE id=(SELECT booking_id FROM charging_sessions WHERE id=?)", session)).isEqualTo("COMPLETED");
        assertThat(scalar("SELECT total_fee FROM v_session_charges WHERE session_id=?", session)).isEqualTo(new BigDecimal("3750.00"));
        assertThat(scalar("SELECT count(*) FROM v_active_sessions")).isEqualTo(0L);
        assertThat(scalar("SELECT count(*) FROM status_audit WHERE entity_id=? AND new_status='COMPLETED'", session)).isEqualTo(1L);
        assertThatThrownBy(() -> execute("UPDATE charging_sessions SET total_fee=1 WHERE id=?", session)).isInstanceOf(SQLException.class);
    }

    @Test void rejectsAcknowledgedStartAndStopForAnotherSessionEvenAfterCompletion() throws Exception {
        UUID first = session("first");
        UUID secondPort = UUID.randomUUID();
        execute("INSERT INTO ports(id,station_id,connector_type,status) VALUES (?,?,'STANDARD','AVAILABLE')", secondPort, STATION);
        UUID secondBooking = UUID.randomUUID();
        execute("INSERT INTO bookings(id,user_id,station_id,port_id,start_at,end_at,status,idempotency_key) VALUES (?,?,?,?,?,?,'IN_USE','second')", secondBooking, USER, STATION, secondPort, START, END);
        UUID second = UUID.randomUUID();
        execute("INSERT INTO charging_sessions(id,booking_id,port_id,tariff_id,price_per_kwh,initial_battery_percent,battery_percent,status,started_at,idempotency_key) VALUES (?,?,?,?,3000,20,20,'STOP_REQUESTED',?,'second')", second, secondBooking, secondPort, TARIFF, START);
        UUID wrongSessionStop = command(second, "STOP", "ACKNOWLEDGED");
        UUID start = command(first, "START", "ACKNOWLEDGED");
        assertThatThrownBy(() -> complete(first, wrongSessionStop, "1")).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> complete(first, start, "1")).isInstanceOf(SQLException.class);
        UUID stop = command(first, "STOP", "ACKNOWLEDGED");
        complete(first, stop, "1");
        assertThatThrownBy(() -> complete(first, wrongSessionStop, "1")).isInstanceOf(SQLException.class);
    }

    @Test void finalizationDoesNotClearADeviceFaultAndRejectsInvalidEnergyOrTime() throws Exception {
        UUID session = session("main");
        UUID stop = command(session, "STOP", "ACKNOWLEDGED");
        assertThatThrownBy(() -> complete(session, stop, "-1")).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> scalar("CALL complete_session(?,?,'NaN'::numeric,?,NULL)", session, stop, END)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("CALL complete_session(?,?,1,?,NULL)", session, stop, START.minusMinutes(1))).isInstanceOf(SQLException.class);
        execute("UPDATE ports SET status='FAULTED' WHERE id=?", PORT);
        complete(session, stop, "0");
        assertThat(scalar("SELECT status FROM ports WHERE id=?", PORT)).isEqualTo("FAULTED");
    }

    @Test void constraintsRejectInvalidStatesTelemetryAndDuplicateSuccessfulPayments() throws Exception {
        UUID session = session("main");
        assertThatThrownBy(() -> execute("UPDATE charging_sessions SET status='PAID' WHERE id=?", session)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("INSERT INTO telemetry(event_id,session_id,port_id,battery_percent,energy_kwh,recorded_at) VALUES (?,?,?,101,1,?)", UUID.randomUUID(), session, PORT, END)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("INSERT INTO telemetry(event_id,session_id,port_id,battery_percent,energy_kwh,recorded_at) VALUES (?,?,?,50,-1,?)", UUID.randomUUID(), session, PORT, END)).isInstanceOf(SQLException.class);
        execute("INSERT INTO payment_transactions(id,session_id,transaction_reference,amount_vnd,status,idempotency_key) VALUES (?,?,?,3000,'SUCCEEDED',?)", UUID.randomUUID(), session, "ref-1", "pay-1");
        assertThatThrownBy(() -> execute("INSERT INTO payment_transactions(id,session_id,transaction_reference,amount_vnd,status,idempotency_key) VALUES (?,?,?,3000,'SUCCEEDED',?)", UUID.randomUUID(), session, "ref-2", "pay-2")).isInstanceOf(SQLException.class);
    }

    @Test void acknowledgedStopFromAnEarlierSessionOnTheSamePortCannotCloseTheNextSession() throws Exception {
        UUID first = session("first");
        UUID oldStop = command(first, "STOP", "ACKNOWLEDGED");
        complete(first, oldStop, "1");
        UUID next = session("next");
        assertThat(scalar("SELECT port_id FROM charging_sessions WHERE id=?", next)).isEqualTo(PORT);
        assertThatThrownBy(() -> complete(next, oldStop, "1"))
                .isInstanceOf(SQLException.class).hasMessageContaining("Matching persisted acknowledged STOP");
        assertThat(scalar("SELECT status FROM charging_sessions WHERE id=?", next)).isEqualTo("STOP_REQUESTED");
    }

    @Test void concurrentStopDeliveriesFreezeOneFinalEnergyAndFee() throws Exception {
        UUID session = session("main");
        UUID stop = command(session, "STOP", "ACKNOWLEDGED");
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<BigDecimal>> results = new ArrayList<>();
            for (String energy : List.of("1.25", "2")) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    if (!go.await(10, TimeUnit.SECONDS)) throw new AssertionError("race not released");
                    return complete(session, stop, energy);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            BigDecimal firstFee = results.get(0).get(15, TimeUnit.SECONDS);
            assertThat(firstFee).isEqualByComparingTo(results.get(1).get(15, TimeUnit.SECONDS));
            assertThat(firstFee.stripTrailingZeros()).isIn(new BigDecimal("3.75E+3"), new BigDecimal("6E+3"));
            assertThat(scalar("SELECT count(*) FROM status_audit WHERE entity_id=? AND new_status='COMPLETED'", session)).isEqualTo(1L);
        } finally { go.countDown(); }
    }

    @Test void recordedEnergyCannotBeClearedToAllowLowerFinalization() throws Exception {
        UUID session = session("energy-reset");
        execute("UPDATE charging_sessions SET energy_kwh=2 WHERE id=?", session);
        assertThatThrownBy(() -> execute("UPDATE charging_sessions SET energy_kwh=NULL WHERE id=?", session))
                .isInstanceOf(SQLException.class).hasMessageContaining("Session telemetry cannot decrease");
        assertThat(scalar("SELECT energy_kwh FROM charging_sessions WHERE id=?", session)).isEqualTo(new BigDecimal("2"));
        UUID stop = command(session, "STOP", "ACKNOWLEDGED");
        assertThatThrownBy(() -> complete(session, stop, "1"))
                .isInstanceOf(SQLException.class).hasMessageContaining("Final energy cannot decrease");
        assertThat(scalar("SELECT status FROM charging_sessions WHERE id=?", session)).isEqualTo("STOP_REQUESTED");
        assertThat(scalar("SELECT total_fee FROM charging_sessions WHERE id=?", session)).isNull();
        assertThat(complete(session, stop, "2")).isEqualByComparingTo("6000");
    }

    @Test void activeSessionViewsAndConstraintsPreserveAllocationAndSnapshot() throws Exception {
        UUID session = session("main");
        assertThat(scalar("SELECT session_id FROM v_active_sessions WHERE session_id=?", session)).isEqualTo(session);
        assertThat(scalar("SELECT status FROM v_port_status WHERE port_id=?", PORT)).isEqualTo("CHARGING");
        assertThatThrownBy(() -> execute("UPDATE charging_sessions SET price_per_kwh=1 WHERE id=?", session)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("UPDATE charging_sessions SET battery_percent=19 WHERE id=?", session)).isInstanceOf(SQLException.class);
        execute("UPDATE charging_sessions SET energy_kwh=2 WHERE id=?", session);
        UUID stop = command(session, "STOP", "ACKNOWLEDGED");
        assertThatThrownBy(() -> complete(session, stop, "1")).isInstanceOf(SQLException.class);
        assertThat(complete(session, stop, "2")).isEqualByComparingTo("6000");
    }

    private List<Object> race(boolean sameKey) throws Exception {
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String key = sameKey ? "same" : "race-" + i;
                futures.add(pool.submit(() -> {
                    try (Connection c = connect()) {
                        c.setAutoCommit(false);
                        ready.countDown();
                        if (!go.await(10, TimeUnit.SECONDS)) throw new AssertionError("race not released");
                        try {
                            Reservation result = reserve(c, key, START, END);
                            c.commit();
                            return result;
                        } catch (SQLException e) { c.rollback(); return e; }
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            return List.of(futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS));
        } finally { go.countDown(); }
    }

    private UUID session(String key) throws Exception {
        // Separate booking windows permit historical sessions on the same physical port.
        long shift = ((Number)scalar("SELECT count(*) FROM charging_sessions")).longValue() * 2;
        UUID booking = reserve(key, START.plusHours(shift), END.plusHours(shift)).bookingId();
        UUID session = UUID.randomUUID();
        execute("INSERT INTO charging_sessions(id,booking_id,port_id,tariff_id,price_per_kwh,initial_battery_percent,battery_percent,status,started_at,idempotency_key) VALUES (?,?,?,?,3000,20,20,'STOP_REQUESTED',?,?)", session, booking, PORT, TARIFF, START, key);
        execute("UPDATE bookings SET status='IN_USE' WHERE id=?", booking);
        return session;
    }

    private UUID command(UUID session, String type, String status) throws SQLException {
        UUID id = UUID.randomUUID();
        execute("INSERT INTO device_commands(id,session_id,port_id,type,status,idempotency_key,acknowledged_at) VALUES (?,?,?,?,?,?,?)", id, session, scalar("SELECT port_id FROM charging_sessions WHERE id=?", session), type, status, id.toString(), status.equals("ACKNOWLEDGED") ? END : null);
        return id;
    }

    private void insertBooking(UUID id, String key, OffsetDateTime start, OffsetDateTime end) throws SQLException {
        execute("INSERT INTO bookings(id,user_id,station_id,port_id,start_at,end_at,status,idempotency_key) VALUES (?,?,?,?,?,?,'CONFIRMED',?)", id, USER, STATION, PORT, start, end, key);
    }

    private Reservation reserve(String key, OffsetDateTime start, OffsetDateTime end) throws SQLException {
        try (Connection c = connect()) { return reserve(c, key, start, end); }
    }

    private Reservation reserve(Connection c, String key, OffsetDateTime start, OffsetDateTime end) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("CALL reserve_port(?,?,?,?,?,NULL,NULL)")) {
            bind(s, USER, STATION, start, end, key);
            try (ResultSet r = s.executeQuery()) { r.next(); return new Reservation(r.getObject(1, UUID.class), r.getObject(2, UUID.class)); }
        }
    }

    private BigDecimal complete(UUID session, UUID command, String energy) throws SQLException {
        return (BigDecimal)scalar("CALL complete_session(?,?,?,?,NULL)", session, command, new BigDecimal(energy), END);
    }

    private static Connection connect() throws SQLException { return DriverManager.getConnection(url, username, password); }
    private static void bind(PreparedStatement s, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
    }
    private static void execute(String sql, Object... values) throws SQLException {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(sql)) { bind(s, values); s.execute(); }
    }
    private static Object scalar(String sql, Object... values) throws SQLException {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, values);
            try (ResultSet r = s.executeQuery()) { r.next(); return r.getObject(1); }
        }
    }
    private record Reservation(UUID bookingId, UUID portId) {}
}
