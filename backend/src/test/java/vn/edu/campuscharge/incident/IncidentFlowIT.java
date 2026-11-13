package vn.edu.campuscharge.incident;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.edu.campuscharge.identity.SecurityTestSupport;
import vn.edu.campuscharge.charging.ChargingRepository;
import vn.edu.campuscharge.device.DeviceMessageHandler;
import vn.edu.campuscharge.device.DeviceMessages.Event;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IncidentFlowIT extends SecurityTestSupport {
    @Autowired ChargingRepository sessions;
    @Autowired DeviceMessageHandler messages;
    UUID incident(String state) {
        UUID id=UUID.randomUUID(); jdbc.update("UPDATE ports SET status=? WHERE id=?",state,PORT);
        jdbc.update("INSERT INTO incidents(id,port_id,description) VALUES (?,?,'test fault')",id,PORT); return id;
    }
    @Test void onlyResolvedIncidentAndFreshDevicePermitPortRestoration() throws Exception {
        UUID id=incident("FAULTED"); var operator=browser("operator@test"); var admin=browser("admin@test");
        mvc.perform(operator.mutation(post("/api/operator/ports/"+PORT+"/restore"))).andExpect(status().isConflict());
        mvc.perform(admin.mutation(put("/api/admin/ports/"+PORT)).contentType("application/json").content("{\"enabled\":true}"))
            .andExpect(status().isConflict());
        mvc.perform(operator.mutation(post("/api/incidents/"+id+"/resolve"))).andExpect(status().isConflict());
        mvc.perform(operator.mutation(post("/api/incidents/"+id+"/acknowledge"))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));
        mvc.perform(operator.mutation(post("/api/incidents/"+id+"/resolve"))).andExpect(status().isConflict());
        recoverAndConfirm(operator);
        mvc.perform(operator.mutation(post("/api/incidents/"+id+"/resolve"))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED"));
        assertThat(jdbc.queryForObject("SELECT status FROM ports WHERE id=?",String.class,PORT)).isEqualTo("FAULTED");
        for(int i=0;i<2;i++) mvc.perform(operator.mutation(post("/api/operator/ports/"+PORT+"/restore"))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AVAILABLE"));
        assertThat(jdbc.queryForObject("SELECT resolved_by FROM incidents WHERE id=?",UUID.class,id)).isEqualTo(OPERATOR);
    }
    @Test void resolutionWaitsForStopAckAndNeverMovesOrCompletesAnActiveSession() throws Exception {
        UUID booking=UUID.randomUUID();
        jdbc.update("INSERT INTO bookings(id,user_id,station_id,port_id,start_at,end_at,idempotency_key) VALUES (?,?,?,?,now()-interval '1 minute',now()+interval '1 hour','active')",booking,USER,STATION,PORT);
        var start=sessions.start(USER,booking,40,"start"); sessions.acknowledgeStart(start.commandId(),PORT,Instant.now());
        messages.event(new Event(UUID.randomUUID(),null,start.sessionId(),STATION,PORT,"FAULT",BigDecimal.ZERO,Instant.now(),1,"OVERHEAT"));
        UUID id=jdbc.queryForObject("SELECT id FROM incidents",UUID.class); var operator=browser("operator@test");
        mvc.perform(operator.mutation(post("/api/incidents/"+id+"/acknowledge"))).andExpect(status().isOk());
        mvc.perform(operator.mutation(post("/api/incidents/"+id+"/resolve"))).andExpect(status().isConflict());
        assertThat(sessions.getInternal(start.sessionId()).status().name()).isEqualTo("STOP_REQUESTED");
        assertThat(sessions.getInternal(start.sessionId()).portId()).isEqualTo(PORT);
        UUID stop=jdbc.queryForObject("SELECT id FROM device_commands WHERE type='STOP'",UUID.class);
        sessions.acknowledgeStop(stop,PORT,BigDecimal.ZERO,Instant.now());
        mvc.perform(operator.mutation(post("/api/incidents/"+id+"/resolve"))).andExpect(status().isConflict());
        recoverAndConfirm(operator);
        mvc.perform(operator.mutation(post("/api/incidents/"+id+"/resolve"))).andExpect(status().isOk());
        assertThat(sessions.getInternal(start.sessionId()).status().name()).isEqualTo("COMPLETED");
    }
    @Test void recoveryPreservesOfflineAndDisabledStatesUntilExplicitSafeRestore() throws Exception {
        UUID id=incident("OFFLINE"); var operator=browser("operator@test");
        mvc.perform(operator.mutation(post("/api/incidents/"+id+"/acknowledge"))).andExpect(status().isOk());
        recoverAndConfirm(operator);
        mvc.perform(operator.mutation(post("/api/incidents/"+id+"/resolve"))).andExpect(status().isOk());
        jdbc.update("UPDATE ports SET last_seen_at=now()-interval '1 hour' WHERE id=?",PORT);
        mvc.perform(operator.mutation(post("/api/operator/ports/"+PORT+"/restore"))).andExpect(status().isConflict());
        jdbc.update("UPDATE ports SET status='DISABLED',last_seen_at=now() WHERE id=?",PORT);
        mvc.perform(operator.mutation(post("/api/operator/ports/"+PORT+"/restore"))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT status FROM ports WHERE id=?",String.class,PORT)).isEqualTo("DISABLED");
    }
    private void recoverAndConfirm(Browser operator) throws Exception {
        String recovery=mvc.perform(operator.mutation(post("/api/operator/ports/"+PORT+"/recover"))).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID recoveryId=UUID.fromString(json.readTree(recovery).get("recoveryId").asText());
        // This positive-path fixture models a device ACK after persisted request/fault times, independent of Java/PG clock precision.
        Instant at=jdbc.queryForObject("""
            SELECT greatest(clock_timestamp(),requested_at+interval '1 millisecond',
                coalesce((SELECT max(recorded_at) FROM device_events WHERE port_id=? AND type='FAULT'),'-infinity'::timestamptz)+interval '1 millisecond',
                coalesce((SELECT max(reported_at) FROM incidents WHERE port_id=?),'-infinity'::timestamptz)+interval '1 millisecond')
            FROM port_recovery_requests WHERE id=?
            """,(r,n)->r.getTimestamp(1).toInstant(),PORT,PORT,recoveryId);
        try { messages.event(new Event(recoveryId,null,null,STATION,PORT,"RECOVERY_ACK",null,at,1,null)); }
        catch(RuntimeException failure) {
            throw new AssertionError("Synthetic ACK="+at+", persisted="+jdbc.queryForMap("SELECT requested_at,superseded_at,(SELECT max(recorded_at) FROM device_events WHERE port_id=? AND type='FAULT') AS fault_at,(SELECT max(reported_at) FROM incidents WHERE port_id=?) AS incident_at FROM port_recovery_requests WHERE id=?",PORT,PORT,recoveryId),failure);
        }
    }
}
