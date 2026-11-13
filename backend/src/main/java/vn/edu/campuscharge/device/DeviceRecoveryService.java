package vn.edu.campuscharge.device;

import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.campuscharge.incident.IncidentService;
import vn.edu.campuscharge.incident.IncidentService.RecoveryView;
import vn.edu.campuscharge.device.simulator.SimulatorScenarioController.Scenario;

@Service
@Profile("!simulator")
public class DeviceRecoveryService {
    private final IncidentService incidents;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<MqttEndpoint> endpoints;
    public DeviceRecoveryService(IncidentService incidents,JdbcTemplate jdbc,@Qualifier("backendMqtt") ObjectProvider<MqttEndpoint> endpoints) {
        this.incidents=incidents; this.jdbc=jdbc; this.endpoints=endpoints;
    }
    public RecoveryView recover(UUID port) {
        var request=incidents.requestRecovery(port); // The request commits before any broker I/O.
        deliver(request); return incidents.recovery(request.recoveryId());
    }
    private void deliver(RecoveryView request) {
        var endpoint=endpoints.getIfAvailable(); if(endpoint==null || request.acknowledgedAt()!=null) return;
        try { endpoint.publish(DeviceMessages.topic(request.stationId(),request.portId(),"scenarios"),new Scenario(request.recoveryId(),request.stationId(),request.portId(),"CLEAR_FAULT",null,request.requestedAt(),1)); }
        catch(RuntimeException unavailable) { LoggerFactory.getLogger(DeviceRecoveryService.class).warn("Recovery request {} remains pending",request.recoveryId()); }
    }
    public void retryPending() {
        requestRecoveryAfterRepair();
        var ids=jdbc.query("SELECT id FROM port_recovery_requests WHERE acknowledged_at IS NULL AND superseded_at IS NULL ORDER BY requested_at,id LIMIT 100",(r,n)->r.getObject(1,UUID.class));
        for(UUID id:ids) deliver(incidents.recovery(id));
    }
    private void requestRecoveryAfterRepair() {
        var ports=jdbc.query("""
            SELECT DISTINCT i.port_id
            FROM incidents i JOIN ports p ON p.id=i.port_id
            WHERE i.status='REPAIR_REPORTED'
              AND (i.report_type='DEVICE' OR p.status IN ('FAULTED','OFFLINE'))
              -- A disconnected charger needs the recovery probe to reconnect before it can receive STOP.
              -- Do not send it while charging is still active; STOP_REQUESTED is already a safe stop transition.
              AND NOT EXISTS (SELECT 1 FROM charging_sessions cs WHERE cs.port_id=i.port_id AND cs.status NOT IN ('STOP_REQUESTED','COMPLETED'))
              AND NOT EXISTS (SELECT 1 FROM incidents other WHERE other.port_id=i.port_id AND other.id<>i.id AND other.status IN ('OPEN','ASSIGNED'))
              AND NOT EXISTS (
                  SELECT 1 FROM port_recovery_requests r WHERE r.port_id=i.port_id AND r.superseded_at IS NULL
                    AND (r.acknowledged_at > greatest(
                          coalesce((SELECT max(recorded_at) FROM device_events WHERE port_id=i.port_id AND type='FAULT'),'-infinity'::timestamptz),
                          coalesce((SELECT max(reported_at) FROM incidents WHERE port_id=i.port_id),'-infinity'::timestamptz))
                      OR (r.acknowledged_at IS NULL AND r.requested_at > greatest(
                          coalesce((SELECT max(recorded_at) FROM device_events WHERE port_id=i.port_id AND type='FAULT'),'-infinity'::timestamptz),
                          coalesce((SELECT max(reported_at) FROM incidents WHERE port_id=i.port_id),'-infinity'::timestamptz))))
              )
            """,(r,n)->r.getObject(1,UUID.class));
        for(UUID port:ports) try { recover(port); }
        catch(ResponseStatusException notReady) { /* Retry after STOP_ACK or the remaining incident is handled. */ }
    }
}
