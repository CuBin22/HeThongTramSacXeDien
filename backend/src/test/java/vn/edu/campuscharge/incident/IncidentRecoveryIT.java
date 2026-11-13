package vn.edu.campuscharge.incident;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import io.moquette.broker.Server;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import vn.edu.campuscharge.charging.*;
import vn.edu.campuscharge.device.*;
import vn.edu.campuscharge.device.DeviceMessages.Event;
import vn.edu.campuscharge.device.simulator.ChargerSimulator;
import vn.edu.campuscharge.device.simulator.SimulatorScenarioController.Scenario;
import vn.edu.campuscharge.identity.SecurityTestSupport;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"device.mqtt.enabled=true","device.scheduling.enabled=false"})
class IncidentRecoveryIT extends SecurityTestSupport {
    static final Server broker=new Server(); static String brokerUrl;
    @DynamicPropertySource static void broker(DynamicPropertyRegistry r) throws Exception {
        int port; try(var socket=new ServerSocket(0)) { port=socket.getLocalPort(); }
        var config=new Properties(); config.setProperty("host","127.0.0.1"); config.setProperty("port",Integer.toString(port));
        config.setProperty("allow_anonymous","true"); config.setProperty("persistence_enabled","false"); config.setProperty("telemetry_enabled","false");
        broker.startServer(config); brokerUrl="tcp://127.0.0.1:"+port; r.add("device.mqtt.url",()->brokerUrl);
    }
    @Autowired ChargingService charging;
    @Autowired ChargingRepository sessions;
    @Autowired MqttDeviceGateway gateway;
    @Autowired MqttEndpoint backendMqtt;
    DroppableEndpoint simulatorMqtt; ChargerSimulator simulator;
    class DroppableEndpoint extends MqttEndpoint {
        volatile boolean dropRecovery;
        volatile boolean dropProbeRejection;
        volatile int recoveryAcks;
        DroppableEndpoint() { super(brokerUrl,"recovery-sim-"+UUID.randomUUID(),"","",json); }
        @Override public void publish(String topic,Object value) {
            if(value instanceof Event event && event.type().equals("RECOVERY_ACK")) { recoveryAcks++; if(dropRecovery) return; }
            if(dropProbeRejection && value instanceof Event event && event.type().equals("COMMAND_REJECTED")) return;
            super.publish(topic,value);
        }
    }
    @BeforeEach void simulator() throws Exception {
        simulatorMqtt=new DroppableEndpoint();
        simulator=new ChargerSimulator(simulatorMqtt,json,Files.createTempDirectory("recovery-sim").resolve("journal.json"),Map.of(PORT,STATION),5,new BigDecimal("3")); simulator.connect();
    }
    @AfterEach void closeSimulator() { simulator.close(); }
    @AfterAll static void closeBroker() { broker.stopServer(); }
    @Test void faultSnapshotsWhileAcknowledgedDoNotBlockConfirmedRecoveryAndResolution() throws Exception {
        simulator.fault(PORT,"PERSISTENT_FAULT");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(jdbc.queryForObject("SELECT count(*) FROM incidents",Integer.class)).isEqualTo(1));
        UUID incident=jdbc.queryForObject("SELECT id FROM incidents",UUID.class); var operator=browser("operator@test");
        mvc.perform(operator.mutation(post("/api/incidents/"+incident+"/acknowledge"))).andExpect(status().isOk());
        simulator.tick();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(jdbc.queryForObject("SELECT count(*) FROM device_events WHERE type='FAULT'",Integer.class)).isGreaterThanOrEqualTo(2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incidents",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM incidents WHERE id=?",String.class,incident)).isEqualTo("ACKNOWLEDGED");
        mvc.perform(operator.mutation(post("/api/operator/ports/"+PORT+"/recover"))).andExpect(status().isAccepted());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(jdbc.queryForObject("SELECT count(*) FROM device_events WHERE type='RECOVERY_ACK'",Integer.class)).isEqualTo(1));
        mvc.perform(operator.mutation(post("/api/incidents/"+incident+"/resolve"))).andExpect(status().isOk());
        mvc.perform(operator.mutation(post("/api/operator/ports/"+PORT+"/restore"))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incidents WHERE status<>'RESOLVED'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM ports WHERE id=?",String.class,PORT)).isEqualTo("AVAILABLE");
    }
    @Test void faultStopDeviceRecoveryResolveRestoreAllowsASecondSessionOnSamePort() throws Exception {
        var first=charging.start(USER,reserve("first"),40,"first-start");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(sessions.getInternal(first.sessionId()).status()).isEqualTo(SessionStatus.CHARGING));
        simulator.fault(PORT,"OVERHEAT");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(sessions.getInternal(first.sessionId()).status()).isEqualTo(SessionStatus.STOP_REQUESTED));
        UUID incident=jdbc.queryForObject("SELECT id FROM incidents",UUID.class); var operator=browser("operator@test");
        mvc.perform(operator.mutation(post("/api/incidents/"+incident+"/acknowledge"))).andExpect(status().isOk());
        simulator.tick();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(jdbc.queryForObject("SELECT count(*) FROM device_events WHERE type='FAULT'",Integer.class)).isGreaterThanOrEqualTo(2));
        mvc.perform(operator.mutation(post("/api/incidents/"+incident+"/resolve"))).andExpect(status().isConflict());
        mvc.perform(operator.mutation(post("/api/operator/ports/"+PORT+"/recover"))).andExpect(status().isConflict());
        gateway.retryPending();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(sessions.getInternal(first.sessionId()).status()).isEqualTo(SessionStatus.COMPLETED));
        mvc.perform(operator.mutation(post("/api/incidents/"+incident+"/resolve"))).andExpect(status().isConflict());
        mvc.perform(operator.mutation(post("/api/operator/ports/"+PORT+"/restore"))).andExpect(status().isConflict());
        mvc.perform(operator.mutation(post("/api/operator/ports/"+PORT+"/recover"))).andExpect(status().isAccepted());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(jdbc.queryForObject("SELECT count(*) FROM device_events WHERE type='RECOVERY_ACK'",Integer.class)).isEqualTo(1));
        mvc.perform(operator.mutation(post("/api/incidents/"+incident+"/resolve"))).andExpect(status().isOk());
        mvc.perform(operator.mutation(post("/api/operator/ports/"+PORT+"/restore"))).andExpect(status().isOk());
        var second=charging.start(USER,reserve("second"),50,"second-start");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(sessions.getInternal(second.sessionId()).status()).isEqualTo(SessionStatus.CHARGING));
        assertThat(second.portId()).isEqualTo(PORT); assertThat(sessions.getInternal(first.sessionId()).portId()).isEqualTo(PORT); assertThat(simulator.startEffects(PORT)).isEqualTo(2);
    }
    @Test void brokerPubackAndOldRecoveryReplayNeverProveAFaultedDeviceHealthy() throws Exception {
        simulator.fault(PORT,"IDLE_FAULT");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(jdbc.queryForObject("SELECT status FROM ports WHERE id=?",String.class,PORT)).isEqualTo("FAULTED"));
        UUID incident=jdbc.queryForObject("SELECT id FROM incidents",UUID.class); var operator=browser("operator@test");
        mvc.perform(operator.mutation(post("/api/incidents/"+incident+"/acknowledge"))).andExpect(status().isOk());
        mvc.perform(operator.mutation(post("/api/incidents/"+incident+"/resolve"))).andExpect(status().isConflict());
        simulatorMqtt.dropRecovery=true;
        String pending=mvc.perform(operator.mutation(post("/api/operator/ports/"+PORT+"/recover"))).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID recovery=UUID.fromString(json.readTree(pending).get("recoveryId").asText());
        await().atMost(Duration.ofSeconds(5)).until(()->simulatorMqtt.recoveryAcks==1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM device_events WHERE type='RECOVERY_ACK'",Integer.class)).isZero();
        mvc.perform(operator.mutation(post("/api/operator/ports/"+PORT+"/restore"))).andExpect(status().isConflict());
        simulatorMqtt.dropRecovery=false;
        mvc.perform(operator.mutation(post("/api/operator/ports/"+PORT+"/recover"))).andExpect(status().isAccepted()).andExpect(jsonPath("$.recoveryId").value(recovery.toString()));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(jdbc.queryForObject("SELECT count(*) FROM device_events WHERE type='RECOVERY_ACK'",Integer.class)).isEqualTo(1));
        mvc.perform(operator.mutation(post("/api/operator/ports/"+PORT+"/recover"))).andExpect(status().isAccepted()).andExpect(jsonPath("$.recoveryId").value(recovery.toString()));
        mvc.perform(operator.mutation(post("/api/incidents/"+incident+"/resolve"))).andExpect(status().isOk());
        mvc.perform(operator.mutation(post("/api/operator/ports/"+PORT+"/restore"))).andExpect(status().isOk());
        Instant requested=jdbc.queryForObject("SELECT requested_at FROM port_recovery_requests WHERE id=?",(r,n)->r.getTimestamp(1).toInstant(),recovery);
        simulator.fault(PORT,"NEW_FAULT");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(jdbc.queryForObject("SELECT status FROM ports WHERE id=?",String.class,PORT)).isEqualTo("FAULTED"));
        int previousAcks=simulatorMqtt.recoveryAcks;
        backendMqtt.publish(DeviceMessages.topic(STATION,PORT,"scenarios"),new Scenario(recovery,STATION,PORT,"CLEAR_FAULT",null,requested,1));
        await().atMost(Duration.ofSeconds(5)).until(()->simulatorMqtt.recoveryAcks>previousAcks);
        simulator.tick();
        simulatorMqtt.dropProbeRejection=true;
        assertThat(simulator.command(new DeviceMessages.CommandMessage(UUID.randomUUID(),UUID.randomUUID(),STATION,PORT,"START",40,null,Instant.now(),1)).type()).isEqualTo("COMMAND_REJECTED");
        UUID latest=jdbc.queryForObject("SELECT id FROM incidents WHERE status='OPEN'",UUID.class);
        mvc.perform(operator.mutation(post("/api/incidents/"+latest+"/acknowledge"))).andExpect(status().isOk());
        mvc.perform(operator.mutation(post("/api/incidents/"+latest+"/resolve"))).andExpect(status().isConflict());
        mvc.perform(operator.mutation(post("/api/operator/ports/"+PORT+"/restore"))).andExpect(status().isConflict());
        var admin=browser("admin@test");
        mvc.perform(admin.mutation(put("/api/admin/ports/"+PORT)).contentType("application/json").content("{\"enabled\":true}")).andExpect(status().isConflict());
    }
}
