package vn.edu.campuscharge.device;

import java.time.*;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.campuscharge.charging.*;
import vn.edu.campuscharge.device.DeviceMessages.CommandMessage;

public class MqttDeviceGateway implements DeviceGateway {
    private static final Logger log=LoggerFactory.getLogger(MqttDeviceGateway.class);
    private final JdbcTemplate jdbc;
    private final ChargingRepository repository;
    private final TransactionTemplate tx;
    private final MqttEndpoint mqtt;
    private final Duration ackTimeout;
    public MqttDeviceGateway(JdbcTemplate jdbc,ChargingRepository repository,PlatformTransactionManager manager,MqttEndpoint mqtt,Duration ackTimeout) {
        this.jdbc=jdbc; this.repository=repository; this.tx=new TransactionTemplate(manager); this.mqtt=mqtt; this.ackTimeout=ackTimeout;
    }
    public AcceptedCommand start(UUID commandId,UUID portId,int battery) { return deliver(commandId,portId,"START",battery,null); }
    public AcceptedCommand stop(UUID commandId,UUID portId,String reason) { return deliver(commandId,portId,"STOP",null,reason); }
    public CommandMessage message(UUID id) {
        return jdbc.queryForObject("""
            SELECT c.*,p.station_id,s.initial_battery_percent,s.battery_capacity_kwh FROM device_commands c
            JOIN ports p ON p.id=c.port_id JOIN charging_sessions s ON s.id=c.session_id WHERE c.id=?
            """,(r,n)->new CommandMessage(r.getObject("id",UUID.class),r.getObject("session_id",UUID.class),r.getObject("station_id",UUID.class),
                r.getObject("port_id",UUID.class),r.getString("type"),r.getInt("initial_battery_percent"),r.getBigDecimal("battery_capacity_kwh"),
                r.getString("reason"),r.getTimestamp("created_at").toInstant(),1),id);
    }
    private AcceptedCommand deliver(UUID id,UUID port,String type,Integer battery,String reason) {
        return tx.execute(status->{
            CommandMessage message=message(id);
            // STOP transitions and ACK processing lock this same row. Keep the lock through the bounded broker PUBACK,
            // so a START publication cannot be overtaken by a STOP transition/publication on another request thread.
            Object[] session=jdbc.queryForObject("SELECT status,completion_reason FROM charging_sessions WHERE id=? FOR UPDATE",
                (r,n)->new Object[]{r.getString("status"),r.getString("completion_reason")},message.sessionId());
            String sessionStatus=(String)session[0];
            var command=repository.command(id);
            if(!message.portId().equals(port) || !message.type().equals(type) || battery!=null && !battery.equals(message.initialBatteryPercent()) || reason!=null && !reason.equals(message.reason()))
                throw new IllegalArgumentException("Delivery payload differs from durable command");
            if(command.status()==DeviceCommandStatus.ACKNOWLEDGED || command.status()==DeviceCommandStatus.FAILED) return null;
            boolean eligible=type.equals("START")?sessionStatus.equals("START_REQUESTED"):
                sessionStatus.equals("STOP_REQUESTED") || sessionStatus.equals("COMPLETED") && "DEVICE_DISCONNECTED".equals(session[1]);
            if(!eligible) {
                if(type.equals("START")) jdbc.update("UPDATE device_commands SET status='FAILED',reason='SUPERSEDED_BY_STOP' WHERE id=? AND status<>'ACKNOWLEDGED'",id);
                return null;
            }
            mqtt.publish(DeviceMessages.topic(message.stationId(),port,"commands"),message);
            jdbc.update("UPDATE device_commands SET status='SENT',sent_at=now(),attempts=attempts+1 WHERE id=? AND status<>'ACKNOWLEDGED'",id);
            return new AcceptedCommand(id);
        });
    }
    public void retryPending() {
        var ids=jdbc.query("""
            SELECT id FROM device_commands WHERE status IN ('PENDING','TIMED_OUT') OR
            (status='SENT' AND sent_at<?) ORDER BY created_at,id LIMIT 100
            """,(r,n)->r.getObject(1,UUID.class),Instant.now().minus(ackTimeout).atOffset(ZoneOffset.UTC));
        for(UUID id:ids) try {
            jdbc.update("UPDATE device_commands SET status='TIMED_OUT' WHERE id=? AND status='SENT' AND sent_at<?",id,Instant.now().minus(ackTimeout).atOffset(ZoneOffset.UTC));
            var command=repository.command(id);
            if(command.type()==DeviceCommand.Type.START) start(id,command.portId(),command.initialBatteryPercent());
            else stop(id,command.portId(),command.reason());
        } catch(RuntimeException unavailable) { log.warn("Device command {} remains retryable",id); }
    }
}
