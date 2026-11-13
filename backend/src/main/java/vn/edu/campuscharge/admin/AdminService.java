package vn.edu.campuscharge.admin;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.campuscharge.api.ApiAccess;
import vn.edu.campuscharge.identity.*;
import vn.edu.campuscharge.booking.BookingRepository;
import vn.edu.campuscharge.incident.IncidentService;

@Service
@Profile("!simulator")
public class AdminService {
    public record TariffView(UUID tariffId,BigDecimal pricePerKwh,BigDecimal dayPricePerKwh,BigDecimal eveningPricePerKwh,
        LocalTime dayStart,LocalTime dayEnd,LocalTime eveningStart,LocalTime eveningEnd,int occupancyGraceMinutes,
        BigDecimal occupancyFeePerMinute,boolean active,Instant effectiveAt) {}
    public record PortPowerView(UUID portId,BigDecimal maxPowerKw) {}
    public record Report(long completedSessions,BigDecimal totalEnergyKwh,BigDecimal totalFeeVnd,BigDecimal paidVnd,long unresolvedIncidents) {}
    private final JdbcTemplate jdbc;
    private final AuthService users;
    private final BookingRepository bookings;
    private final IncidentService incidents;
    public AdminService(JdbcTemplate jdbc,AuthService users,BookingRepository bookings,IncidentService incidents) { this.jdbc=jdbc; this.users=users; this.bookings=bookings; this.incidents=incidents; }
    public List<AuthService.UserView> users() {
        return jdbc.query("SELECT id FROM users ORDER BY email,id",(r,n)->r.getObject(1,UUID.class)).stream().map(users::find).toList();
    }
    public AuthService.UserView updateUser(UUID id,UserRole role,Boolean enabled) {
        if(role==null || enabled==null) throw bad("Role and enabled are required");
        if(jdbc.update("UPDATE users SET role=?,enabled=? WHERE id=?",role.name(),enabled,id)==0) throw ApiAccess.missing(); return users.find(id);
    }
    private static ResponseStatusException bad(String detail) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,detail); }
    private static String text(String value,String name) {
        if(value==null || value.isBlank() || value.trim().length()>100) throw bad(name+" is required (maximum 100 characters)"); return value.trim();
    }
    public BookingRepository.StationView createStation(String name,String connector) {
        UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO stations(id,name,connector_type) VALUES (?,?,?)",id,text(name,"Name"),text(connector,"Connector")); return station(id);
    }
    private BookingRepository.StationView station(UUID id) { return bookings.stations().stream().filter(s->s.stationId().equals(id)).findFirst().orElseThrow(ApiAccess::missing); }
    public BookingRepository.StationView updateStation(UUID id,String name,Boolean active) {
        if(active==null) throw bad("Active is required");
        if(jdbc.update("UPDATE stations SET name=?,active=? WHERE id=?",text(name,"Name"),active,id)==0) throw ApiAccess.missing(); return station(id);
    }
    public List<BookingRepository.PortView> chargers() { return bookings.allPorts(); }
    @Transactional public BookingRepository.PortView createCharger(String mode,String connector,BigDecimal power) {
        mode=mode==null?null:mode.toUpperCase(Locale.ROOT);
        connector=connector==null?null:connector.toUpperCase(Locale.ROOT);
        validateCharger(mode,connector,power);
        jdbc.execute("SELECT pg_advisory_xact_lock(20261028, 1)");
        UUID station=jdbc.query("SELECT id FROM stations WHERE name='Bãi xe Bệnh viện' ORDER BY id LIMIT 1 FOR UPDATE",(r,n)->r.getObject(1,UUID.class))
            .stream().findFirst().orElseGet(()->{ UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO stations(id,name,connector_type) VALUES (?, 'Bãi xe Bệnh viện','STANDARD')",id); return id; });
        jdbc.queryForObject("SELECT id FROM stations WHERE id=? FOR UPDATE",UUID.class,station);
        UUID id=insertPort(station,mode,connector,power);
        return bookings.port(id);
    }
    @Transactional public IncidentService.PortView createPort(UUID station,String mode,BigDecimal power) {
        mode=mode==null?"AC":mode;
        if(!Set.of("AC","DC").contains(mode)) throw bad("Chọn loại cổng AC hoặc DC");
        BigDecimal maxPower=power==null?(mode.equals("AC")?new BigDecimal("10"):new BigDecimal("30")):power;
        var connector=jdbc.query("SELECT connector_type FROM stations WHERE id=? FOR UPDATE",(r,n)->r.getString(1),station);
        if(connector.isEmpty()) throw ApiAccess.missing(); UUID id=UUID.randomUUID();
        String connectorType=connector.getFirst(); validateCharger(mode,connectorType,maxPower);
        id=insertPort(station,mode,connectorType,maxPower);
        return new IncidentService.PortView(id,"OFFLINE",null);
    }
    private static void validateCharger(String mode,String connector,BigDecimal power) {
        if(!Set.of("AC","DC").contains(mode)) throw bad("Chọn loại trụ AC hoặc DC");
        if(!Set.of("STANDARD","TYPE_2","GB_T","CCS2","CHADEMO").contains(connector)) throw bad("Chọn chuẩn cổng sạc hợp lệ");
        validatePower(mode,power);
    }
    private static void validatePower(String mode,BigDecimal power) {
        BigDecimal min=mode.equals("AC")?new BigDecimal("10"):new BigDecimal("30");
        BigDecimal max=mode.equals("AC")?new BigDecimal("22"):new BigDecimal("150");
        if(power==null || power.compareTo(min)<0 || power.compareTo(max)>0)
            throw bad("Công suất trụ "+mode+" phải từ "+min.stripTrailingZeros().toPlainString()+" đến "+max.stripTrailingZeros().toPlainString()+" kW");
    }
    private UUID insertPort(UUID station,String mode,String connector,BigDecimal power) {
        validatePower(mode,power);
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO ports(id,station_id,connector_type,charging_mode,max_power_kw,status) VALUES (?,?,?,?,?,'OFFLINE')",id,station,connector,mode,power);
        return id;
    }
    public IncidentService.PortView updatePort(UUID id,Boolean enabled) { if(enabled==null) throw bad("Enabled is required"); return incidents.setEnabled(id,enabled); }
    @Transactional
    public PortPowerView updatePortPower(UUID id,BigDecimal maxPowerKw) {
        String mode=jdbc.query("SELECT charging_mode FROM ports WHERE id=? FOR UPDATE",(r,n)->r.getString(1),id).stream().findFirst().orElseThrow(ApiAccess::missing);
        validatePower(mode,maxPowerKw);
        Boolean charging=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM charging_sessions WHERE port_id=? AND status<>'COMPLETED')",Boolean.class,id);
        if(Boolean.TRUE.equals(charging)) throw ApiAccess.conflict("Không thể thay đổi công suất khi cổng đang có phiên chưa kết thúc");
        if(jdbc.update("UPDATE ports SET max_power_kw=? WHERE id=?",maxPowerKw,id)==0) throw ApiAccess.missing();
        return new PortPowerView(id,maxPowerKw);
    }
    public List<TariffView> tariffs() {
        return jdbc.query("SELECT * FROM tariffs ORDER BY effective_at DESC,id",(r,n)->new TariffView(r.getObject("id",UUID.class),r.getBigDecimal("price_per_kwh"),r.getBigDecimal("day_price_per_kwh"),r.getBigDecimal("evening_price_per_kwh"),
            time(r,"day_start"),time(r,"day_end"),time(r,"evening_start"),time(r,"evening_end"),r.getInt("occupancy_grace_minutes"),r.getBigDecimal("occupancy_fee_per_minute"),r.getBoolean("active"),r.getTimestamp("effective_at").toInstant()));
    }
    private static LocalTime time(java.sql.ResultSet r,String name) throws java.sql.SQLException { return r.getTime(name)==null?null:r.getTime(name).toLocalTime(); }
    public TariffView createTariff(BigDecimal dayPrice,BigDecimal eveningPrice,LocalTime dayStart,LocalTime dayEnd,
        LocalTime eveningStart,LocalTime eveningEnd,int graceMinutes,BigDecimal feePerMinute,Instant effective,BigDecimal legacyPrice) {
        if(dayPrice==null && eveningPrice==null) {
            if(legacyPrice==null || legacyPrice.signum()<0) throw bad("Nhập đơn giá hoặc lịch giá hợp lệ");
            UUID legacyId=UUID.randomUUID(); Instant legacyAt=effective==null?Instant.now():effective;
            jdbc.update("INSERT INTO tariffs(id,station_id,price_per_kwh,effective_at) VALUES (?,NULL,?,?)",legacyId,legacyPrice,legacyAt.atOffset(ZoneOffset.UTC));
            return tariffs().stream().filter(item->item.tariffId().equals(legacyId)).findFirst().orElseThrow(ApiAccess::missing);
        }
        if(dayPrice==null || eveningPrice==null || dayPrice.signum()<=0 || eveningPrice.signum()<=0) throw bad("Nhập hai đơn giá lớn hơn 0");
        if(dayStart==null || dayEnd==null || eveningStart==null || eveningEnd==null || !dayStart.isBefore(dayEnd) || !dayEnd.equals(eveningStart) || !eveningStart.isBefore(eveningEnd)) throw bad("Hai khung giờ phải liền nhau, không chồng lấn và có giờ bắt đầu trước giờ kết thúc");
        if(graceMinutes<0 || graceMinutes>1440 || feePerMinute==null || feePerMinute.signum()<0) throw bad("Phí chiếm chỗ hoặc thời gian miễn phí không hợp lệ");
        UUID id=UUID.randomUUID(); Instant at=effective==null?Instant.now():effective;
        jdbc.update("INSERT INTO tariffs(id,station_id,price_per_kwh,day_price_per_kwh,evening_price_per_kwh,day_start,day_end,evening_start,evening_end,occupancy_grace_minutes,occupancy_fee_per_minute,effective_at) VALUES (?,NULL,?,?,?,?,?,?,?,?,?,?)",
            id,dayPrice,dayPrice,eveningPrice,dayStart,dayEnd,eveningStart,eveningEnd,graceMinutes,feePerMinute,at.atOffset(ZoneOffset.UTC));
        return tariffs().stream().filter(item->item.tariffId().equals(id)).findFirst().orElseThrow(ApiAccess::missing);
    }
    public Report report() {
        var totals=jdbc.queryForObject("SELECT count(*) AS sessions,coalesce(sum(energy_kwh),0) AS energy,coalesce(sum(total_fee),0) AS fees FROM v_session_charges",(r,n)->new BigDecimal[]{r.getBigDecimal("sessions"),r.getBigDecimal("energy"),r.getBigDecimal("fees")});
        BigDecimal paid=jdbc.queryForObject("SELECT coalesce(sum(amount_vnd),0) FROM payment_transactions WHERE status='SUCCEEDED'",BigDecimal.class);
        long open=jdbc.queryForObject("SELECT count(*) FROM incidents WHERE status<>'RESOLVED'",Long.class);
        return new Report(totals[0].longValueExact(),totals[1],totals[2],paid,open);
    }
}
