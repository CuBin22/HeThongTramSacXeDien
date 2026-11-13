package vn.edu.campuscharge.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.campuscharge.charging.*;
import vn.edu.campuscharge.device.DeviceMessages.*;

@Service
@Profile("!simulator")
public class DeviceMessageHandler {
    private final JdbcTemplate jdbc;
    private final ChargingRepository sessions;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final Duration heartbeatTimeout;
    private final Instant monitoringStarted=Instant.now();
    private record PortHeartbeat(String status,Instant lastSeen) {}
    public DeviceMessageHandler(JdbcTemplate jdbc,ChargingRepository sessions,ObjectMapper json,PlatformTransactionManager manager) {
        this(jdbc,sessions,json,manager,15);
    }
    @Autowired
    public DeviceMessageHandler(JdbcTemplate jdbc,ChargingRepository sessions,ObjectMapper json,PlatformTransactionManager manager,
        @Value("${device.heartbeat-timeout-seconds:15}") long heartbeatTimeoutSeconds) {
        this.jdbc=jdbc; this.sessions=sessions; this.json=json; this.tx=new TransactionTemplate(manager);
        this.heartbeatTimeout=Duration.ofSeconds(heartbeatTimeoutSeconds);
    }
    public void accept(String topic,byte[] payload) {
        try {
            if(topic.endsWith("/telemetry")) { var message=json.readValue(payload,Telemetry.class); DeviceMessages.topicMatches(topic,message.stationId(),message.portId(),"telemetry"); telemetry(message); }
            else if(topic.endsWith("/events")) { var message=json.readValue(payload,Event.class); DeviceMessages.topicMatches(topic,message.stationId(),message.portId(),"events"); event(message); }
        } catch(java.io.IOException malformed) { throw new IllegalArgumentException("Malformed device message",malformed); }
    }
    private void validate(UUID event,UUID station,UUID port,Instant timestamp,int version) {
        if(event==null || station==null || port==null || timestamp==null || version!=1 || timestamp.isAfter(Instant.now().plusSeconds(5)))
            throw new IllegalArgumentException("Invalid device message envelope");
        var known=jdbc.query("SELECT station_id FROM ports WHERE id=?",(r,n)->r.getObject(1,UUID.class),port);
        if(known.isEmpty() || !known.getFirst().equals(station)) throw new IllegalArgumentException("Unknown station/port");
    }
    public boolean telemetry(Telemetry message) {
        validate(message.eventId(),message.stationId(),message.portId(),message.timestamp(),message.schemaVersion());
        if(message.sessionId()==null || message.batteryPercent()==null || message.batteryPercent()<0 || message.batteryPercent()>100 ||
           message.energyKwh()==null || message.energyKwh().signum()<0) throw new IllegalArgumentException("Invalid battery or energy");
        return Boolean.TRUE.equals(tx.execute(status->{
            var session=lockSession(message.sessionId(),message.portId());
            var duplicate=jdbc.query("SELECT * FROM telemetry WHERE event_id=?",(r,n)->new Telemetry(r.getObject("event_id",UUID.class),r.getObject("session_id",UUID.class),message.stationId(),r.getObject("port_id",UUID.class),
                r.getInt("battery_percent"),r.getBigDecimal("energy_kwh"),r.getTimestamp("recorded_at").toInstant(),r.getInt("schema_version")),message.eventId());
            if(!duplicate.isEmpty()) {
                var old=duplicate.getFirst();
                if(!old.sessionId().equals(message.sessionId()) || !old.portId().equals(message.portId()) || !old.batteryPercent().equals(message.batteryPercent()) ||
                   old.energyKwh().compareTo(message.energyKwh())!=0 || Duration.between(old.timestamp(),message.timestamp()).abs().compareTo(Duration.ofNanos(1000))>=0)
                    throw new IllegalArgumentException("Telemetry event ID reused with different payload");
                return false;
            }
            var previous=jdbc.query("SELECT recorded_at FROM telemetry WHERE session_id=? ORDER BY recorded_at DESC LIMIT 1",(r,n)->r.getTimestamp(1).toInstant(),message.sessionId());
            if(session.status()==SessionStatus.COMPLETED || message.timestamp().isBefore(session.startedAt()) ||
               !previous.isEmpty() && !message.timestamp().isAfter(previous.getFirst()) || message.batteryPercent()<session.batteryPercent() ||
               session.energyKwh()!=null && message.energyKwh().compareTo(session.energyKwh())<0) throw new IllegalArgumentException("Stale or decreasing telemetry");
            jdbc.update("INSERT INTO telemetry(event_id,session_id,port_id,battery_percent,energy_kwh,recorded_at,schema_version) VALUES (?,?,?,?,?,?,?)",
                message.eventId(),message.sessionId(),message.portId(),message.batteryPercent(),message.energyKwh(),utc(message.timestamp()),message.schemaVersion());
            jdbc.update("UPDATE charging_sessions SET battery_percent=?,energy_kwh=? WHERE id=?",message.batteryPercent(),message.energyKwh(),message.sessionId());
            seen(message.portId(),message.timestamp());
            var target=jdbc.queryForObject("SELECT target_type,target_battery_percent,target_budget_vnd,price_per_kwh FROM charging_sessions WHERE id=?",(r,n)->new Object[]{r.getString(1),(Integer)r.getObject(2),r.getBigDecimal(3),r.getBigDecimal(4)},message.sessionId());
            boolean reached=switch((String)target[0]) {
                case "BATTERY_PERCENT" -> message.batteryPercent()>=(Integer)target[1];
                case "BUDGET" -> message.energyKwh().multiply((BigDecimal)target[3]).compareTo((BigDecimal)target[2])>=0;
                default -> message.batteryPercent()>=100;
            };
            if(reached) sessions.requestStop(message.sessionId(),"CHARGE_TARGET_REACHED");
            return true;
        }));
    }
    private ChargingRepository.SessionView lockSession(UUID session,UUID port) {
        var ids=jdbc.query("SELECT id FROM charging_sessions WHERE id=? AND port_id=? FOR UPDATE",(r,n)->r.getObject(1,UUID.class),session,port);
        if(ids.isEmpty()) throw new IllegalArgumentException("Session does not match port");
        return sessions.getInternal(session);
    }
    public boolean event(Event message) {
        validate(message.eventId(),message.stationId(),message.portId(),message.timestamp(),message.schemaVersion());
        if(!Set.of("HEARTBEAT","START_ACK","STOP_ACK","COMMAND_REJECTED","FAULT","NEAR_FULL","FULL","RECOVERY_ACK").contains(message.type())) throw new IllegalArgumentException("Unknown event type");
        return Boolean.TRUE.equals(tx.execute(status->{
            // Acquire the state locks before the event FK's KEY SHARE lock. Concurrent ACKs must not
            // both hold KEY SHARE and then attempt to upgrade it to the session's FOR UPDATE lock.
            if(message.type().equals("RECOVERY_ACK")) lockRecoveryPort(message.portId());
            else if(message.type().equals("HEARTBEAT")) lockNewPort(message.portId());
            else if(Set.of("FAULT","COMMAND_REJECTED").contains(message.type())) {
                UUID station=jdbc.queryForObject("SELECT station_id FROM ports WHERE id=?",UUID.class,message.portId());
                jdbc.queryForObject("SELECT id FROM stations WHERE id=? FOR UPDATE",UUID.class,station);
                jdbc.query("SELECT id FROM charging_sessions WHERE port_id=? AND status<>'COMPLETED' FOR UPDATE",(r,n)->r.getObject(1,UUID.class),message.portId());
            } else if(Set.of("START_ACK","STOP_ACK").contains(message.type()) && message.sessionId()!=null) lockSession(message.sessionId(),message.portId());
            String payload;
            try { payload=json.writeValueAsString(message); } catch(Exception invalid) { throw new IllegalArgumentException("Invalid event",invalid); }
            int inserted=jdbc.update("INSERT INTO device_events(event_id,port_id,session_id,command_id,type,recorded_at,payload) VALUES (?,?,?,?,?,?,CAST(? AS jsonb)) ON CONFLICT DO NOTHING",
                message.eventId(),message.portId(),message.sessionId(),message.commandId(),message.type(),utc(message.timestamp()),payload);
            if(inserted==0) {
                Boolean same=jdbc.queryForObject("SELECT payload=CAST(? AS jsonb) FROM device_events WHERE event_id=?",Boolean.class,payload,message.eventId());
                if(!Boolean.TRUE.equals(same)) throw new IllegalArgumentException("Device event ID reused with different payload");
                return false;
            }
            if(message.type().equals("START_ACK") || message.type().equals("STOP_ACK") || message.type().equals("COMMAND_REJECTED")) {
                if(message.commandId()==null || message.sessionId()==null) throw new IllegalArgumentException("Acknowledgement requires command/session");
                var command=sessions.command(message.commandId());
                if(!command.sessionId().equals(message.sessionId()) || !command.portId().equals(message.portId())) throw new IllegalArgumentException("Acknowledgement identity mismatch");
                if(message.type().equals("START_ACK")) sessions.acknowledgeStart(message.commandId(),message.portId(),message.timestamp());
                else if(message.type().equals("STOP_ACK")) sessions.acknowledgeStop(message.commandId(),message.portId(),message.energyKwh(),message.timestamp());
                else {
                    if(sessions.getInternal(message.sessionId()).status()!=SessionStatus.COMPLETED) unsafePort(message.portId(),"FAULTED","COMMAND_REJECTED: "+message.description(),message.timestamp());
                    jdbc.update("UPDATE device_commands SET status='FAILED',reason=? WHERE id=? AND status<>'ACKNOWLEDGED'",message.description(),message.commandId());
                    if(command.type()==DeviceCommand.Type.START) sessions.requestStop(message.sessionId(),"START_REJECTED");
                }
            } else if(message.type().equals("RECOVERY_ACK")) {
                if(message.commandId()!=null || message.sessionId()!=null) throw new IllegalArgumentException("Recovery acknowledgement must describe an idle port");
                var requests=jdbc.query("SELECT requested_at FROM port_recovery_requests WHERE id=? AND port_id=? AND superseded_at IS NULL FOR UPDATE",(r,n)->r.getTimestamp(1).toInstant(),message.eventId(),message.portId());
                Instant barrier=jdbc.queryForObject("""
                    SELECT greatest(
                        coalesce((SELECT max(recorded_at) FROM device_events WHERE port_id=? AND type='FAULT'),'-infinity'::timestamptz),
                        coalesce((SELECT max(reported_at) FROM incidents WHERE port_id=?),'-infinity'::timestamptz))
                    """,(r,n)->r.getTimestamp(1).toInstant(),message.portId(),message.portId());
                if(requests.isEmpty() || message.timestamp().isBefore(requests.getFirst()) || !message.timestamp().isAfter(barrier)) throw new IllegalArgumentException("Unknown or stale device recovery acknowledgement");
                jdbc.update("UPDATE port_recovery_requests SET acknowledged_at=coalesce(acknowledged_at,?) WHERE id=?",utc(message.timestamp()),message.eventId());
            } else if(message.type().equals("FAULT")) {
                var times=jdbc.query("SELECT last_seen_at FROM ports WHERE id=? AND last_seen_at>?",(r,n)->r.getTimestamp(1).toInstant(),message.portId(),utc(message.timestamp()));
                if(!times.isEmpty()) throw new IllegalArgumentException("Stale fault event");
                unsafePort(message.portId(),"FAULTED","DEVICE_FAULT: "+message.description(),message.timestamp());
            }
            seen(message.portId(),message.timestamp());
            if(message.type().equals("HEARTBEAT")) activateNewPort(message.portId());
            return true;
        }));
    }
    private void seen(UUID port,Instant at) { jdbc.update("UPDATE ports SET last_seen_at=greatest(last_seen_at,?) WHERE id=?",utc(at),port); }
    private void lockNewPort(UUID port) {
        Boolean recoverable=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM ports p JOIN stations s ON s.id=p.station_id
                WHERE p.id=? AND p.status='OFFLINE' AND s.active
                    AND NOT EXISTS (SELECT 1 FROM incidents i WHERE i.port_id=p.id AND i.status<>'RESOLVED')
                    AND NOT EXISTS (SELECT 1 FROM charging_sessions cs WHERE cs.port_id=p.id AND cs.status<>'COMPLETED'))
            """,Boolean.class,port);
        if(!Boolean.TRUE.equals(recoverable)) return;
        UUID station=jdbc.queryForObject("SELECT station_id FROM ports WHERE id=?",UUID.class,port);
        jdbc.queryForObject("SELECT id FROM stations WHERE id=? FOR UPDATE",UUID.class,station);
        jdbc.query("SELECT id FROM charging_sessions WHERE port_id=? AND status<>'COMPLETED' FOR UPDATE",(r,n)->r.getObject(1,UUID.class),port);
        jdbc.query("SELECT id FROM bookings WHERE port_id=? AND status IN ('CONFIRMED','IN_USE') ORDER BY start_at,id FOR UPDATE",(r,n)->r.getObject(1,UUID.class),port);
        jdbc.queryForObject("SELECT id FROM ports WHERE id=? FOR NO KEY UPDATE",UUID.class,port);
    }
    private void activateNewPort(UUID port) {
        jdbc.update("""
            UPDATE ports p SET status='AVAILABLE'
            WHERE p.id=? AND p.status='OFFLINE' AND p.last_seen_at>=?
                AND EXISTS (SELECT 1 FROM stations s WHERE s.id=p.station_id AND s.active)
                AND NOT EXISTS (SELECT 1 FROM incidents i WHERE i.port_id=p.id AND i.status<>'RESOLVED')
                AND NOT EXISTS (SELECT 1 FROM charging_sessions cs WHERE cs.port_id=p.id AND cs.status<>'COMPLETED')
            """,port,utc(Instant.now().minus(heartbeatTimeout)));
    }
    private void lockRecoveryPort(UUID port) {
        UUID station=jdbc.queryForObject("SELECT station_id FROM ports WHERE id=?",UUID.class,port);
        jdbc.queryForObject("SELECT id FROM stations WHERE id=? FOR UPDATE",UUID.class,station);
        var sessions=jdbc.query("SELECT id FROM charging_sessions WHERE port_id=? AND status<>'COMPLETED' FOR UPDATE",(r,n)->r.getObject(1,UUID.class),port);
        jdbc.query("SELECT id FROM bookings WHERE port_id=? AND status IN ('CONFIRMED','IN_USE') ORDER BY start_at,id FOR UPDATE",(r,n)->r.getObject(1,UUID.class),port);
        jdbc.queryForObject("SELECT id FROM ports WHERE id=? FOR NO KEY UPDATE",UUID.class,port);
        if(!sessions.isEmpty()) throw new IllegalArgumentException("Cannot confirm recovery while a session is active");
    }
    private static OffsetDateTime utc(Instant at) { return at.atOffset(ZoneOffset.UTC); }
    public void expireHeartbeats(Instant now,Duration timeout) {
        Instant cutoff=now.minus(timeout);
        var ports=jdbc.query("SELECT id FROM ports WHERE status NOT IN ('OFFLINE','FAULTED','DISABLED') AND (last_seen_at<? OR (last_seen_at IS NULL AND ?<?))",
            (r,n)->r.getObject(1,UUID.class),utc(cutoff),utc(monitoringStarted),utc(cutoff));
        for(UUID port:ports) tx.executeWithoutResult(status->unsafePort(port,"OFFLINE","HEARTBEAT_TIMEOUT",now,cutoff));
    }
    private void unsafePort(UUID port,String state,String description,Instant at) {
        unsafePort(port,state,description,at,null);
    }
    private void unsafePort(UUID port,String state,String description,Instant at,Instant heartbeatCutoff) {
        UUID station=jdbc.queryForObject("SELECT station_id FROM ports WHERE id=?",UUID.class,port);
        jdbc.queryForObject("SELECT id FROM stations WHERE id=? FOR UPDATE",UUID.class,station);
        var active=jdbc.query("SELECT id FROM charging_sessions WHERE port_id=? AND status<>'COMPLETED' FOR UPDATE",(r,n)->r.getObject(1,UUID.class),port);
        // complete_session locks session -> booking -> port; faults use that order under the station lock too.
        var bookings=jdbc.query("SELECT id FROM bookings WHERE port_id=? AND status IN ('CONFIRMED','IN_USE') ORDER BY start_at,id FOR UPDATE",(r,n)->r.getObject(1,UUID.class),port);
        var heartbeat=jdbc.queryForObject("SELECT status,last_seen_at FROM ports WHERE id=? FOR NO KEY UPDATE",(r,n)->new PortHeartbeat(
            r.getString("status"),r.getTimestamp("last_seen_at")==null?null:r.getTimestamp("last_seen_at").toInstant()),port);
        // Re-read after every potentially blocking state lock, then hold the port lock through all side effects.
        if(heartbeatCutoff!=null && (!Set.of("AVAILABLE","RESERVED","CHARGING").contains(heartbeat.status()) ||
            !(heartbeat.lastSeen()==null?monitoringStarted.isBefore(heartbeatCutoff):heartbeat.lastSeen().isBefore(heartbeatCutoff)))) return;
        for(UUID session:active) sessions.requestStop(session,description);
        jdbc.update("UPDATE ports SET status=? WHERE id=? AND status<>'DISABLED'",state,port);
        jdbc.update("""
            INSERT INTO incidents(port_id,session_id,description) SELECT ?,?,?
            WHERE NOT EXISTS (SELECT 1 FROM incidents WHERE port_id=? AND status<>'RESOLVED')
            """,port,active.isEmpty()?null:active.getFirst(),description,port);
        // A heartbeat timeout means a STOP_ACK cannot arrive. Freeze billing from the last
        // persisted telemetry now, but keep the port OFFLINE and the incident unresolved.
        if(state.equals("OFFLINE")) for(UUID session:active) sessions.completeAfterDisconnect(session,at);
        for(UUID booking:bookings) {
            var replacement=jdbc.query("""
                SELECT p.id FROM ports p JOIN bookings b ON b.id=? JOIN stations s ON s.id=p.station_id
                WHERE b.status='CONFIRMED' AND NOT EXISTS (SELECT 1 FROM charging_sessions cs WHERE cs.booking_id=b.id)
                AND p.station_id=b.station_id AND p.connector_type=s.connector_type AND s.active AND p.status='AVAILABLE' AND p.id<>?
                AND NOT EXISTS (SELECT 1 FROM charging_sessions cs WHERE cs.port_id=p.id AND cs.status<>'COMPLETED')
                AND NOT EXISTS (SELECT 1 FROM bookings occupied WHERE occupied.port_id=p.id AND occupied.status IN ('CONFIRMED','IN_USE')
                    AND tstzrange(occupied.start_at,occupied.end_at,'[)') && tstzrange(b.start_at,b.end_at,'[)'))
                ORDER BY p.id LIMIT 1 FOR UPDATE OF p
                """,(r,n)->r.getObject(1,UUID.class),booking,port);
            if(!replacement.isEmpty()) jdbc.update("UPDATE bookings SET port_id=? WHERE id=?",replacement.getFirst(),booking);
            else jdbc.update("UPDATE bookings SET status='UNSERVICEABLE' WHERE id=? AND status='CONFIRMED' AND NOT EXISTS (SELECT 1 FROM charging_sessions WHERE booking_id=?)",booking,booking);
        }
    }
}
