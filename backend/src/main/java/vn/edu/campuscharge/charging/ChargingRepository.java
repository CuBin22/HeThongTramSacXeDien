package vn.edu.campuscharge.charging;

import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.campuscharge.api.ApiAccess;
import vn.edu.campuscharge.booking.BookingRepository;
import vn.edu.campuscharge.booking.BookingStatus;

@Repository
@org.springframework.context.annotation.Profile("!simulator")
public class ChargingRepository {
    private final JdbcTemplate jdbc;
    private final BookingRepository bookings;
    public ChargingRepository(JdbcTemplate jdbc,BookingRepository bookings) {
        this.jdbc=jdbc; this.bookings=bookings;
    }
    public record Command(UUID commandId,UUID sessionId,UUID portId,DeviceCommand.Type type,DeviceCommandStatus status,int initialBatteryPercent,String reason) {}
    public record SessionView(UUID sessionId,UUID bookingId,UUID portId,SessionStatus status,int initialBatteryPercent,int batteryPercent,
        BigDecimal energyKwh,BigDecimal powerKw,BigDecimal pricePerKwh,BigDecimal totalFee,Instant startedAt,Instant chargingStartedAt,Instant endedAt,String deviceStatus,String completionReason,
        boolean nearFull,UUID commandId,DeviceCommandStatus commandStatus,boolean deliveryPending,String commandReason,
        UUID vehicleId,BigDecimal batteryCapacityKwh,String targetType,Integer targetBatteryPercent,BigDecimal targetBudgetVnd,
        BigDecimal energyFeeVnd,BigDecimal occupancyFeeVnd,BigDecimal amountDueVnd,BigDecimal externalPaidVnd,BigDecimal remainingDueVnd,
        String paymentStatus,String paymentRedirectUrl,String portName) {}
    public record StartOptions(UUID vehicleId,int initialBatteryPercent,String targetType,Integer targetBatteryPercent,BigDecimal targetBudgetVnd) {}
    public record Quote(BigDecimal pricePerKwh,BigDecimal estimatedEnergyKwh,BigDecimal estimatedCostVnd,BigDecimal batteryCapacityKwh,String connectorType) {}
    private record OwnedSession(UUID userId,String key,SessionView view) {}
    private OwnedSession row(ResultSet r,int n) throws SQLException {
        int battery=r.getInt("battery_percent");
        return new OwnedSession(r.getObject("user_id",UUID.class),r.getString("idempotency_key"),new SessionView(r.getObject("id",UUID.class),
            r.getObject("booking_id",UUID.class),r.getObject("port_id",UUID.class),SessionStatus.valueOf(r.getString("status")),
            r.getInt("initial_battery_percent"),battery,r.getBigDecimal("energy_kwh"),r.getBigDecimal("power_kw"),r.getBigDecimal("price_per_kwh"),r.getBigDecimal("total_fee"),
            r.getTimestamp("started_at").toInstant(),r.getTimestamp("charging_started_at")==null?null:r.getTimestamp("charging_started_at").toInstant(),
            r.getTimestamp("ended_at")==null?null:r.getTimestamp("ended_at").toInstant(),r.getString("device_status"),
            r.getString("completion_reason"),battery>=90,r.getObject("command_id",UUID.class),r.getString("command_status")==null?null:DeviceCommandStatus.valueOf(r.getString("command_status")),
            "PENDING".equals(r.getString("command_status")) || "TIMED_OUT".equals(r.getString("command_status")),r.getString("command_reason"),
            r.getObject("vehicle_id",UUID.class),r.getBigDecimal("battery_capacity_kwh"),r.getString("target_type"), (Integer)r.getObject("target_battery_percent"),r.getBigDecimal("target_budget_vnd"),
            r.getBigDecimal("energy_fee_vnd"),r.getBigDecimal("occupancy_fee_vnd"),r.getBigDecimal("amount_due_vnd"),r.getBigDecimal("external_paid_vnd"),r.getBigDecimal("remaining_due_vnd"),
            r.getString("payment_status"),r.getString("payment_redirect_url"),r.getString("port_name")));
    }
    private static final String SESSION_SQL="""
        SELECT cs.*,b.user_id,p.status AS device_status,p.display_name AS port_name,c.id AS command_id,c.status AS command_status,c.reason AS command_reason,
          settlement.energy_fee_vnd,settlement.occupancy_fee_vnd,settlement.amount_due_vnd,paid.paid_vnd AS external_paid_vnd,
          due.remaining_due_vnd,payment.status AS payment_status,payment.redirect_url AS payment_redirect_url,
          CASE WHEN previous.recorded_at IS NOT NULL AND latest.recorded_at>previous.recorded_at
            THEN (latest.energy_kwh-previous.energy_kwh)*3600/NULLIF(extract(epoch FROM latest.recorded_at-previous.recorded_at),0)
            ELSE NULL END AS power_kw
        FROM charging_sessions cs JOIN bookings b ON b.id=cs.booking_id JOIN ports p ON p.id=cs.port_id
        LEFT JOIN LATERAL (SELECT dc.* FROM device_commands dc WHERE dc.session_id=cs.id
          AND dc.type=CASE WHEN cs.status IN ('STOP_REQUESTED','COMPLETED') THEN 'STOP' ELSE 'START' END
          ORDER BY dc.created_at,dc.id LIMIT 1) c ON true
        LEFT JOIN LATERAL (SELECT t.energy_kwh,t.recorded_at FROM telemetry t WHERE t.session_id=cs.id ORDER BY t.recorded_at DESC LIMIT 1) latest ON true
        LEFT JOIN LATERAL (SELECT t.energy_kwh,t.recorded_at FROM telemetry t WHERE t.session_id=cs.id AND t.recorded_at<latest.recorded_at ORDER BY t.recorded_at DESC LIMIT 1) previous ON true
        LEFT JOIN session_settlements settlement ON settlement.session_id=cs.id
        LEFT JOIN LATERAL (SELECT coalesce(sum(amount_vnd),0) AS paid_vnd FROM payment_transactions WHERE session_id=cs.id AND status='SUCCEEDED') paid ON true
        LEFT JOIN LATERAL (SELECT status,redirect_url FROM payment_transactions WHERE session_id=cs.id AND status IN ('PENDING','SUCCEEDED') ORDER BY created_at DESC,id DESC LIMIT 1) payment ON true
        CROSS JOIN LATERAL (SELECT greatest(coalesce(settlement.amount_due_vnd,0)-coalesce(paid.paid_vnd,0),0) AS remaining_due_vnd) due
        WHERE cs.id=?
        """;
    private OwnedSession session(UUID id,boolean lock) {
        var rows=jdbc.query(SESSION_SQL+(lock?" FOR UPDATE OF cs":""),this::row,id);
        if(rows.isEmpty()) throw ApiAccess.missing();
        return rows.getFirst();
    }
    public SessionView get(UUID user,UUID id) { var row=session(id,false); ApiAccess.owner(row.userId(),user); return row.view(); }
    public SessionView getInternal(UUID id) { return session(id,false).view(); }
    public List<SessionView> list(UUID user) {
        return jdbc.query("SELECT session_id FROM v_active_sessions WHERE user_id=? UNION ALL SELECT session_id FROM v_session_charges WHERE user_id=?",
            (r,n)->r.getObject(1,UUID.class),user,user).stream().map(this::getInternal).sorted(Comparator.comparing(SessionView::startedAt).reversed()).toList();
    }
    public Command command(UUID id) {
        var rows=jdbc.query("SELECT c.*,s.initial_battery_percent FROM device_commands c JOIN charging_sessions s ON s.id=c.session_id WHERE c.id=?",
            (r,n)->new Command(r.getObject("id",UUID.class),r.getObject("session_id",UUID.class),r.getObject("port_id",UUID.class),
                DeviceCommand.Type.valueOf(r.getString("type")),DeviceCommandStatus.valueOf(r.getString("status")),r.getInt("initial_battery_percent"),r.getString("reason")),id);
        if(rows.isEmpty()) throw ApiAccess.missing();
        return rows.getFirst();
    }
    /** Compatibility entry point for older internal callers; public API requires an explicitly saved vehicle. */
    @Transactional
    public Command start(UUID user,UUID bookingId,int battery,String key) {
        var booking=bookings.find(bookingId,false); ApiAccess.owner(booking.userId(),user);
        UUID vehicle=booking.vehicleId()!=null?booking.vehicleId():jdbc.query("SELECT id FROM vehicles WHERE user_id=? AND connector_type=(SELECT connector_type FROM ports WHERE id=?) ORDER BY created_at,id LIMIT 1",
            (r,n)->r.getObject(1,UUID.class),user,booking.portId()).stream().findFirst().orElse(null);
        if(vehicle==null) {
            vehicle=UUID.randomUUID();
            String connector=jdbc.queryForObject("SELECT connector_type FROM ports WHERE id=?",String.class,booking.portId());
            jdbc.update("INSERT INTO vehicles(id,user_id,name,model,connector_type,battery_capacity_kwh) VALUES(?,?,?, ?,?,3)",vehicle,user,"Xe máy mặc định","Xe máy điện",connector);
        }
        return start(user,bookingId,new StartOptions(vehicle,battery,"FULL",null,null),key);
    }
    @Transactional
    public Quote quote(UUID user,UUID bookingId,StartOptions options) {
        if(options==null || options.targetType()==null) throw ApiAccess.conflict("Hãy chọn mục tiêu sạc");
        var booking=bookings.find(bookingId,false); ApiAccess.owner(booking.userId(),user);
        if(booking.vehicleId()!=null && !booking.vehicleId().equals(options.vehicleId())) throw ApiAccess.conflict("Phiên sạc phải dùng đúng phương tiện đã chọn khi đặt cổng");
        var vehicle=jdbc.query("SELECT v.connector_type,v.battery_capacity_kwh,p.connector_type AS port_connector FROM vehicles v JOIN ports p ON p.id=? WHERE v.id=? AND v.user_id=?",
            (r,n)->new Object[]{r.getString("connector_type"),r.getBigDecimal("battery_capacity_kwh"),r.getString("port_connector")},booking.portId(),options.vehicleId(),user);
        if(vehicle.isEmpty()) throw ApiAccess.conflict("Hãy chọn phương tiện đã lưu trong tài khoản");
        if(!vehicle.getFirst()[0].equals(vehicle.getFirst()[2])) throw ApiAccess.conflict("Loại cổng không tương thích với phương tiện đã chọn");
        String target=options.targetType();
        if(!Set.of("FULL","BATTERY_PERCENT","BUDGET").contains(target)) throw ApiAccess.conflict("Mục tiêu sạc không hợp lệ");
        if(options.initialBatteryPercent()<0 || options.initialBatteryPercent()>99) throw ApiAccess.conflict("Pin hiện tại phải từ 0 đến 99%");
        if(target.equals("BATTERY_PERCENT") && (options.targetBatteryPercent()==null || options.targetBatteryPercent()<=options.initialBatteryPercent() || options.targetBatteryPercent()>100)) throw ApiAccess.conflict("Mức pin mục tiêu phải cao hơn pin hiện tại và tối đa 100%");
        if(target.equals("BUDGET") && (options.targetBudgetVnd()==null || options.targetBudgetVnd().signum()<=0)) throw ApiAccess.conflict("Ngân sách phải lớn hơn 0 đồng");
        var prices=jdbc.query("SELECT tariff_price_at(id,now()) FROM tariffs WHERE active AND effective_at<=now() AND (station_id=? OR station_id IS NULL) ORDER BY (station_id IS NOT NULL) DESC,effective_at DESC,id LIMIT 1 FOR SHARE",(r,n)->r.getBigDecimal(1),booking.stationId());
        if(prices.isEmpty()) throw ApiAccess.conflict("Trạm chưa có biểu giá hiện hành");
        BigDecimal price=prices.getFirst();
        BigDecimal capacity=(BigDecimal)vehicle.getFirst()[1];
        int targetPercent=target.equals("FULL")?100:target.equals("BATTERY_PERCENT")?options.targetBatteryPercent():100;
        BigDecimal energy=capacity.multiply(BigDecimal.valueOf(Math.max(0,targetPercent-options.initialBatteryPercent()))).movePointLeft(2);
        if(target.equals("BUDGET") && price.signum()>0) energy=energy.min(options.targetBudgetVnd().divide(price,8,java.math.RoundingMode.DOWN));
        BigDecimal cost=energy.multiply(price).setScale(0,java.math.RoundingMode.CEILING);
        return new Quote(price,energy,cost,capacity,(String)vehicle.getFirst()[0]);
    }
    @Transactional
    public Command start(UUID user,UUID bookingId,StartOptions options,String key) {
        int battery=options.initialBatteryPercent();
        var allocated=bookings.find(bookingId,false); ApiAccess.owner(allocated.userId(),user);
        // The station lock serializes reserve, start and fault reassignment before allocation changes.
        jdbc.queryForObject("SELECT id FROM stations WHERE id=? FOR UPDATE",UUID.class,allocated.stationId());
        var booking=bookings.find(bookingId,true);
        ApiAccess.owner(booking.userId(),user);
        var existing=jdbc.query("SELECT id FROM charging_sessions WHERE booking_id=?",(r,n)->r.getObject(1,UUID.class),bookingId);
        if(!existing.isEmpty()) {
            var old=session(existing.getFirst(),false);
            if(!old.key().equals(key) || old.view().initialBatteryPercent()!=battery || !Objects.equals(old.view().vehicleId(),options.vehicleId()) || !old.view().targetType().equals(options.targetType()) || !Objects.equals(old.view().targetBatteryPercent(),options.targetBatteryPercent()) || !Objects.equals(old.view().targetBudgetVnd(),options.targetBudgetVnd())) throw ApiAccess.conflict("Idempotency key was already used with different charging details");
            return command(jdbc.queryForObject("SELECT id FROM device_commands WHERE session_id=? AND type='START'",UUID.class,old.view().sessionId()));
        }
        Instant now=Instant.now();
        if(booking.status()!=BookingStatus.CONFIRMED || now.isBefore(booking.startAt())
            || !now.isBefore(booking.startAt().plus(Duration.ofMinutes(15))) || !now.isBefore(booking.endAt()))
            throw ApiAccess.conflict("Đặt chỗ chỉ được bắt đầu từ giờ đã chọn đến hết 15 phút chờ.");
        Boolean ready=jdbc.queryForObject("SELECT p.status='AVAILABLE' AND s.active FROM ports p JOIN stations s ON s.id=p.station_id WHERE p.id=? FOR UPDATE OF p",Boolean.class,booking.portId());
        if(!Boolean.TRUE.equals(ready)) throw ApiAccess.conflict("Port or station is unavailable");
        var tariffs=jdbc.query("""
            SELECT id,tariff_price_at(id,now()) AS price_per_kwh FROM tariffs WHERE active AND effective_at<=now() AND (station_id=? OR station_id IS NULL)
            ORDER BY (station_id IS NOT NULL) DESC,effective_at DESC,id LIMIT 1 FOR SHARE
            """,(r,n)->Map.entry(r.getObject("id",UUID.class),r.getBigDecimal("price_per_kwh")),booking.stationId());
        if(tariffs.isEmpty()) throw ApiAccess.conflict("No active tariff");
        Quote quote=quote(user,bookingId,options);
        UUID vehicleId=options.vehicleId();
        UUID sessionId=UUID.randomUUID(),commandId=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO charging_sessions(id,booking_id,port_id,tariff_id,price_per_kwh,initial_battery_percent,battery_percent,energy_kwh,idempotency_key,
              battery_capacity_kwh,
              vehicle_id,target_type,target_battery_percent,target_budget_vnd)
            VALUES (?,?,?,?,?,?,?,0,?,?,?,?,?,?)
            """,sessionId,bookingId,booking.portId(),tariffs.getFirst().getKey(),tariffs.getFirst().getValue(),battery,battery,key,
                quote.batteryCapacityKwh(),vehicleId,options.targetType(),options.targetBatteryPercent(),options.targetBudgetVnd());
        jdbc.update("UPDATE bookings SET status='IN_USE' WHERE id=?",bookingId);
        jdbc.update("INSERT INTO device_commands(id,session_id,port_id,type,idempotency_key) VALUES (?,?,?,'START',?)",
            commandId,sessionId,booking.portId(),sessionId+":START:"+key);
        return command(commandId);
    }
    @Transactional
    public Command stop(UUID user,UUID id,String key,String reason) {
        var row=session(id,true); ApiAccess.owner(row.userId(),user);
        return stopLocked(id,key,reason,row);
    }
    @Transactional
    public Command requestStop(UUID id,String reason) {
        return stopLocked(id,"automatic",reason,session(id,true));
    }
    @Transactional
    public void completeAfterDisconnect(UUID sessionId,Instant detectedAt) {
        var stopCommands=jdbc.query("SELECT id FROM device_commands WHERE session_id=? AND type='STOP' ORDER BY created_at,id LIMIT 1",
            (r,n)->r.getObject(1,UUID.class),sessionId);
        if(stopCommands.isEmpty()) return;
        jdbc.queryForObject("CALL complete_disconnected_session(?,?,?,NULL)",(r,n)->r.getBigDecimal("p_total_fee"),
            sessionId,stopCommands.getFirst(),detectedAt.atOffset(ZoneOffset.UTC));
    }
    private Command stopLocked(UUID id,String key,String reason,OwnedSession row) {
        var commands=jdbc.query("SELECT id FROM device_commands WHERE session_id=? AND type='STOP' ORDER BY created_at,id LIMIT 1",(r,n)->r.getObject(1,UUID.class),id);
        if(!commands.isEmpty()) return command(commands.getFirst());
        if(row.view().status()==SessionStatus.COMPLETED) throw ApiAccess.conflict("Session already completed");
        UUID commandId=UUID.randomUUID();
        jdbc.update("UPDATE charging_sessions SET status='STOP_REQUESTED' WHERE id=?",id);
        jdbc.update("INSERT INTO device_commands(id,session_id,port_id,type,idempotency_key,reason) VALUES (?,?,?,'STOP',?,?)",
            commandId,id,row.view().portId(),id+":STOP:"+key,reason);
        return command(commandId);
    }
    public void markSent(UUID id) {
        jdbc.update("UPDATE device_commands SET status='SENT',sent_at=now(),attempts=attempts+1 WHERE id=? AND status IN ('PENDING','TIMED_OUT')",id);
    }
    private Command matchingAcknowledgement(UUID commandId,UUID portId,DeviceCommand.Type type,Instant at) {
        Command command=command(commandId);
        var session=session(command.sessionId(),true).view();
        Instant earliest=type==DeviceCommand.Type.STOP && session.chargingStartedAt()!=null?session.chargingStartedAt():session.startedAt();
        if(command.type()!=type || !command.portId().equals(portId) || at==null || at.isBefore(earliest))
            throw ApiAccess.conflict("Acknowledgement does not match the command, port or session time");
        return command;
    }
    private void acknowledge(Command command,Instant at) {
        jdbc.update("UPDATE device_commands SET status='ACKNOWLEDGED',acknowledged_at=coalesce(acknowledged_at,?) WHERE id=?",
            at.atOffset(ZoneOffset.UTC),command.commandId());
    }
    @Transactional
    public void acknowledgeStart(UUID commandId,UUID portId,Instant at) {
        var command=matchingAcknowledgement(commandId,portId,DeviceCommand.Type.START,at);
        acknowledge(command,at);
        jdbc.update("UPDATE charging_sessions SET charging_started_at=coalesce(charging_started_at,?) WHERE id=? AND status IN ('START_REQUESTED','STOP_REQUESTED')",
            at.atOffset(ZoneOffset.UTC),command.sessionId());
        int changed=jdbc.update("UPDATE charging_sessions SET status='CHARGING' WHERE id=? AND status='START_REQUESTED'",command.sessionId());
        if(changed>0) jdbc.update("UPDATE ports SET status='CHARGING' WHERE id=? AND status IN ('AVAILABLE','RESERVED')",portId);
    }
    @Transactional
    public void acknowledgeStop(UUID commandId,UUID portId,BigDecimal energy,Instant at) {
        var command=matchingAcknowledgement(commandId,portId,DeviceCommand.Type.STOP,at);
        if(energy==null || energy.signum()<0) throw ApiAccess.conflict("Final energy must be non-negative");
        var session=session(command.sessionId(),true).view();
        if(session.status()==SessionStatus.COMPLETED && "DEVICE_DISCONNECTED".equals(session.completionReason())) {
            acknowledge(command,at);
            return;
        }
        acknowledge(command,at);
        jdbc.queryForObject("CALL complete_session(?,?,?,?,NULL)",(r,n)->r.getBigDecimal("p_total_fee"),
            command.sessionId(),commandId,energy,at.atOffset(ZoneOffset.UTC));
    }
}
