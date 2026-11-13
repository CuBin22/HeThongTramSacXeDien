package vn.edu.campuscharge.booking;

import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.campuscharge.api.ApiAccess;

@Repository
@org.springframework.context.annotation.Profile("!simulator")
public class BookingRepository {
    private final JdbcTemplate jdbc;
    public BookingRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record BookingView(UUID bookingId, UUID userId, UUID stationId, UUID portId, Instant startAt, Instant endAt, BookingStatus status, UUID vehicleId) {}
    public record StationView(UUID stationId, String name, boolean active, String connectorType) {}
    public record PortView(UUID portId, UUID stationId, String connectorType, String chargingMode, String status, String deviceStatus,
        UUID bookingId, UUID sessionId, Instant lastSeenAt, java.math.BigDecimal maxPowerKw,
        Instant bookingStartAt, Instant bookingEndAt, Instant nextBookingStartAt, Instant nextBookingEndAt,
        String incidentDescription, String incidentStatus, String displayName) {}
    public record CurrentTariffView(java.math.BigDecimal pricePerKwh, Instant effectiveAt,
        java.math.BigDecimal dayPricePerKwh,java.math.BigDecimal eveningPricePerKwh,LocalTime dayStart,LocalTime dayEnd,
        LocalTime eveningStart,LocalTime eveningEnd,int occupancyGraceMinutes,java.math.BigDecimal occupancyFeePerMinute) {}
    public static BookingView map(ResultSet r, int row) throws SQLException {
        return new BookingView(r.getObject("id",UUID.class),r.getObject("user_id",UUID.class),r.getObject("station_id",UUID.class),
            r.getObject("port_id",UUID.class),r.getTimestamp("start_at").toInstant(),r.getTimestamp("end_at").toInstant(),BookingStatus.valueOf(r.getString("status")),r.getObject("vehicle_id",UUID.class));
    }
    @Transactional
    public BookingView reserve(UUID user,UUID station,UUID port,UUID vehicle,Instant start,Instant end,String key) {
        UUID id=jdbc.queryForObject("CALL reserve_port(?,?,?,?,?,?,?,NULL,NULL)",(r,n)->r.getObject("p_booking_id",UUID.class),
            user,station,port,vehicle,start.atOffset(ZoneOffset.UTC),end.atOffset(ZoneOffset.UTC),key);
        return find(id,false);
    }
    public List<PortView> availablePorts(UUID user,UUID station,UUID vehicle,Instant start,Instant end) {
        return jdbc.query("""
            SELECT v.* FROM v_port_status v JOIN ports p ON p.id=v.port_id JOIN stations s ON s.id=p.station_id
            JOIN vehicles vehicle ON vehicle.id=? AND vehicle.user_id=? AND vehicle.connector_type=p.connector_type
            WHERE v.station_id=? AND s.active AND p.status='AVAILABLE' AND v.device_status='AVAILABLE' AND v.session_id IS NULL
              AND NOT EXISTS (SELECT 1 FROM bookings b WHERE b.port_id=p.id AND b.status IN ('CONFIRMED','IN_USE')
                AND (b.status='IN_USE' OR b.start_at + interval '15 minutes' > now())
                AND tstzrange(b.start_at,b.end_at,'[)') && tstzrange(?,?,'[)'))
            ORDER BY p.id
            """,(r,n)->portView(r,false),vehicle,user,station,start.atOffset(ZoneOffset.UTC),end.atOffset(ZoneOffset.UTC));
    }
    public BookingView find(UUID id,boolean lock) {
        var rows=jdbc.query("SELECT * FROM bookings WHERE id=?"+(lock?" FOR UPDATE":""),BookingRepository::map,id);
        if (rows.isEmpty()) throw ApiAccess.missing();
        return rows.getFirst();
    }
    public List<BookingView> list(UUID user) { return jdbc.query("SELECT * FROM bookings WHERE user_id=? ORDER BY start_at DESC",BookingRepository::map,user); }
    public void cancel(UUID id) { jdbc.update("UPDATE bookings SET status='CANCELLED' WHERE id=?",id); }
    public List<StationView> stations() {
        return jdbc.query("SELECT * FROM stations ORDER BY name,id",(r,n)->new StationView(r.getObject("id",UUID.class),r.getString("name"),r.getBoolean("active"),r.getString("connector_type")));
    }
    public List<PortView> ports(UUID station) {
        return jdbc.query("SELECT * FROM v_port_status WHERE station_id=? ORDER BY port_id",(r,n)->portView(r,true),station);
    }
    public List<PortView> allPorts() {
        return jdbc.query("SELECT * FROM v_port_status ORDER BY display_name",(r,n)->portView(r,true));
    }
    public PortView port(UUID id) { return allPorts().stream().filter(port -> port.portId().equals(id)).findFirst().orElseThrow(ApiAccess::missing); }
    private static Instant instant(ResultSet row,String column) throws SQLException { return row.getTimestamp(column)==null?null:row.getTimestamp(column).toInstant(); }
    private static PortView portView(ResultSet row,boolean includeUpcomingReservation) throws SQLException {
        Instant nextStart=instant(row,"next_booking_start_at");
        String status=row.getString("status");
        if(includeUpcomingReservation && "AVAILABLE".equals(status) && nextStart!=null) status="RESERVED";
        return new PortView(row.getObject("port_id",UUID.class),row.getObject("station_id",UUID.class),row.getString("connector_type"),row.getString("charging_mode"),
            status,row.getString("device_status"),row.getObject("booking_id",UUID.class),row.getObject("session_id",UUID.class),instant(row,"last_seen_at"),row.getBigDecimal("max_power_kw"),
            instant(row,"booking_start_at"),instant(row,"booking_end_at"),nextStart,instant(row,"next_booking_end_at"),row.getString("incident_description"),row.getString("incident_status"),row.getString("display_name"));
    }
    public List<CurrentTariffView> currentTariff(UUID station) {
        return jdbc.query("""
            SELECT tariff_price_at(id,now()) AS price_per_kwh,effective_at,day_price_per_kwh,evening_price_per_kwh,
                   day_start,day_end,evening_start,evening_end,occupancy_grace_minutes,occupancy_fee_per_minute
            FROM tariffs WHERE active AND effective_at<=now() AND station_id IS NULL
            ORDER BY effective_at DESC,id LIMIT 1
            """,(r,n)->new CurrentTariffView(r.getBigDecimal("price_per_kwh"),r.getTimestamp("effective_at").toInstant(),
                r.getBigDecimal("day_price_per_kwh"),r.getBigDecimal("evening_price_per_kwh"),r.getTime("day_start")==null?null:r.getTime("day_start").toLocalTime(),
                r.getTime("day_end")==null?null:r.getTime("day_end").toLocalTime(),r.getTime("evening_start")==null?null:r.getTime("evening_start").toLocalTime(),
                r.getTime("evening_end")==null?null:r.getTime("evening_end").toLocalTime(),r.getInt("occupancy_grace_minutes"),r.getBigDecimal("occupancy_fee_per_minute")),station);
    }
}
