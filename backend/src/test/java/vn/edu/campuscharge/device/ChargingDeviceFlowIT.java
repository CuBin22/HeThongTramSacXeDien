package vn.edu.campuscharge.device;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import io.moquette.broker.Server;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import vn.edu.campuscharge.api.ApiTestSupport;
import vn.edu.campuscharge.charging.*;
import vn.edu.campuscharge.device.DeviceMessages.*;
import vn.edu.campuscharge.device.simulator.ChargerSimulator;
import vn.edu.campuscharge.device.simulator.SimulatorScenarioController.Scenario;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"device.mqtt.enabled=true","device.scheduling.enabled=false"})
@Import(ChargingDeviceFlowIT.MessageConfiguration.class)
class ChargingDeviceFlowIT extends ApiTestSupport {
    static final Server broker=new Server();
    static String brokerUrl;
    @DynamicPropertySource static void mqtt(DynamicPropertyRegistry r) throws Exception {
        int port;
        try(var socket=new ServerSocket(0)) { port=socket.getLocalPort(); }
        Properties config=new Properties(); config.setProperty("port",Integer.toString(port));
        config.setProperty("host","127.0.0.1"); config.setProperty("allow_anonymous","true");
        config.setProperty("persistence_enabled","false"); config.setProperty("telemetry_enabled","false");
        broker.startServer(config); brokerUrl="tcp://127.0.0.1:"+port;
        r.add("device.mqtt.url",()->brokerUrl);
    }
    @Autowired ChargingService charging;
    @Autowired ChargingRepository sessions;
    @Autowired MqttDeviceGateway gateway;
    @Autowired TestDeviceMessageHandler messages;
    @MockitoSpyBean(name="backendMqtt") MqttEndpoint backendMqtt;
    ChargerSimulator simulator;
    Path journal;
    @BeforeEach void device() throws Exception {
        messages.drop=(topic,payload)->false;
        journal=Files.createTempDirectory("campus-device-test").resolve("journal.json");
        simulator=new ChargerSimulator(new MqttEndpoint(brokerUrl,"sim-test-"+UUID.randomUUID(),"","",json),json,journal,Map.of(PORT,STATION),5,new BigDecimal("3"));
        simulator.connect();
    }
    @AfterEach void closeDevice() { simulator.close(); }
    @AfterAll static void closeBroker() { broker.stopServer(); }
    ChargingRepository.SessionView start(int battery) throws Exception {
        var view=charging.start(USER,reserve("booking"),battery,"start");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(sessions.getInternal(view.sessionId()).status()).isEqualTo(SessionStatus.CHARGING));
        return sessions.getInternal(view.sessionId());
    }
    @Test void advancesToNearFullThenWaitsForStopAcknowledgementAndFreezesOneFee() throws Exception {
        var view=start(85);
        simulator.tick();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(sessions.getInternal(view.sessionId()).nearFull()).isTrue());
        assertThat(sessions.getInternal(view.sessionId()).batteryPercent()).isEqualTo(90);
        simulator.tick(); simulator.tick();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(sessions.getInternal(view.sessionId()).status()).isEqualTo(SessionStatus.STOP_REQUESTED));
        assertThat(sessions.getInternal(view.sessionId()).totalFee()).isNull();
        gateway.retryPending();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(sessions.getInternal(view.sessionId()).status()).isEqualTo(SessionStatus.COMPLETED));
        assertThat(sessions.getInternal(view.sessionId()).totalFee()).isEqualByComparingTo("1350");
        gateway.retryPending();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM device_commands WHERE type='STOP'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM status_audit WHERE entity_table='charging_sessions' AND new_status='COMPLETED'",Integer.class)).isEqualTo(1);
        assertThat(simulator.startEffects(PORT)).isEqualTo(1);
        assertThat(simulator.stopEffects(PORT)).isEqualTo(1);
    }
    @Test void rejectsInvalidStaleAndDecreasingTelemetryWithoutChangingSession() throws Exception {
        var view=start(30); Instant at=Instant.now();
        var valid=new Telemetry(UUID.randomUUID(),view.sessionId(),STATION,PORT,40,new BigDecimal("0.3"),at,1);
        assertThat(messages.telemetry(valid)).isTrue();
        assertThat(messages.telemetry(valid)).isFalse();
        for(var invalid:List.of(
            new Telemetry(UUID.randomUUID(),view.sessionId(),STATION,PORT,101,new BigDecimal("0.4"),at.plusMillis(1),1),
            new Telemetry(UUID.randomUUID(),view.sessionId(),STATION,PORT,39,new BigDecimal("0.4"),at.plusMillis(1),1),
            new Telemetry(UUID.randomUUID(),view.sessionId(),STATION,PORT,41,new BigDecimal("0.2"),at.plusMillis(1),1),
            new Telemetry(UUID.randomUUID(),view.sessionId(),STATION,PORT,41,new BigDecimal("0.4"),at.minusMillis(1),1),
            new Telemetry(UUID.randomUUID(),view.sessionId(),STATION,PORT,41,new BigDecimal("0.4"),at.plusSeconds(30),1),
            new Telemetry(UUID.randomUUID(),view.sessionId(),STATION,PORT2,41,new BigDecimal("0.4"),at.plusMillis(1),1)))
            assertThatThrownBy(()->messages.telemetry(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThat(sessions.getInternal(view.sessionId()).batteryPercent()).isEqualTo(40);
        assertThat(sessions.getInternal(view.sessionId()).energyKwh()).isEqualByComparingTo("0.3");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM telemetry",Integer.class)).isEqualTo(1);
    }
    @Test void heartbeatLossKeepsSessionUnconfirmedAndCreatesOneIncident() throws Exception {
        var view=start(40); simulator.tick();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(sessions.getInternal(view.sessionId()).batteryPercent()).isEqualTo(45));
        messages.expireHeartbeats(Instant.now().plusSeconds(60),Duration.ofSeconds(10));
        messages.expireHeartbeats(Instant.now().plusSeconds(60),Duration.ofSeconds(10));
        assertThat(sessions.getInternal(view.sessionId()).deviceStatus()).isEqualTo("OFFLINE");
        assertThat(sessions.getInternal(view.sessionId()).status()).isEqualTo(SessionStatus.STOP_REQUESTED);
        assertThat(sessions.getInternal(view.sessionId()).totalFee()).isNull();
        assertThat(sessions.getInternal(view.sessionId()).portId()).isEqualTo(PORT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incidents",Integer.class)).isEqualTo(1);
    }
    @Test void faultStopsActiveSessionAndNeverFreesFaultedPort() throws Exception {
        var view=start(40); simulator.fault(PORT,"OVERHEAT"); simulator.fault(PORT,"OVERHEAT");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(sessions.getInternal(view.sessionId()).status()).isEqualTo(SessionStatus.STOP_REQUESTED));
        assertThat(sessions.getInternal(view.sessionId()).deviceStatus()).isEqualTo("FAULTED");
        gateway.retryPending();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(sessions.getInternal(view.sessionId()).status()).isEqualTo(SessionStatus.COMPLETED));
        assertThat(sessions.getInternal(view.sessionId()).deviceStatus()).isEqualTo("FAULTED");
        assertThat(sessions.getInternal(view.sessionId()).portId()).isEqualTo(PORT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incidents",Integer.class)).isEqualTo(1);
    }
    @Test void measuredOverheatAboveThresholdOnChargingPortCreatesIncidentAndStopsSession() throws Exception {
        var view=start(40);
        var scenario=new Scenario(UUID.randomUUID(),STATION,PORT,"OVERHEAT_WHILE_CHARGING","demo",Instant.now(),1,new BigDecimal("85.0"));
        backendMqtt.publish(DeviceMessages.topic(STATION,PORT,"scenarios"),scenario);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(sessions.getInternal(view.sessionId()).status()).isEqualTo(SessionStatus.STOP_REQUESTED));
        gateway.retryPending();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(sessions.getInternal(view.sessionId()).status()).isEqualTo(SessionStatus.COMPLETED));
        assertThat(sessions.getInternal(view.sessionId()).deviceStatus()).isEqualTo("FAULTED");
        assertThat(jdbc.queryForObject("SELECT description FROM incidents WHERE port_id=?",String.class,PORT)).contains("85 C", "70.0 C");
    }
    @Test void reassignsOnlyUnstartedBookingsAndMarksThoseWithNoCapacityUnserviceable() {
        jdbc.update("INSERT INTO ports(id,station_id) VALUES (?,?)",PORT2,STATION);
        Instant a=Instant.now().plusSeconds(3600),b=a.plusSeconds(3600),c=b.plusSeconds(3600),d=c.plusSeconds(3600);
        UUID first=UUID.randomUUID(),second=UUID.randomUUID();
        jdbc.update("INSERT INTO bookings(id,user_id,station_id,port_id,start_at,end_at,idempotency_key) VALUES (?,?,?,?,?,?,?)",first,USER,STATION,PORT,a.atOffset(ZoneOffset.UTC),b.atOffset(ZoneOffset.UTC),"future-1");
        jdbc.update("INSERT INTO bookings(id,user_id,station_id,port_id,start_at,end_at,idempotency_key) VALUES (?,?,?,?,?,?,?)",second,USER,STATION,PORT,c.atOffset(ZoneOffset.UTC),d.atOffset(ZoneOffset.UTC),"future-2");
        jdbc.update("INSERT INTO bookings(user_id,station_id,port_id,start_at,end_at,idempotency_key) VALUES (?,?,?,?,?,?)",OTHER,STATION,PORT2,c.atOffset(ZoneOffset.UTC),d.atOffset(ZoneOffset.UTC),"occupied");
        messages.event(new Event(UUID.randomUUID(),null,null,STATION,PORT,"FAULT",null,Instant.now(),1,"OVERHEAT"));
        assertThat(jdbc.queryForObject("SELECT port_id FROM bookings WHERE id=?",UUID.class,first)).isEqualTo(PORT2);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,second)).isEqualTo("UNSERVICEABLE");
    }
    @Test void duplicateAndDelayedStartCannotRestartAStoppedSessionEvenAfterSimulatorRestart() throws Exception {
        var view=start(40); UUID startId=view.commandId();
        var startMessage=gateway.message(startId);
        backendMqtt.publish(DeviceMessages.topic(STATION,PORT,"commands"),startMessage);
        charging.stop(USER,view.sessionId(),"stop");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(sessions.getInternal(view.sessionId()).status()).isEqualTo(SessionStatus.COMPLETED));
        backendMqtt.publish(DeviceMessages.topic(STATION,PORT,"commands"),startMessage);
        simulator.close();
        simulator=new ChargerSimulator(new MqttEndpoint(brokerUrl,"sim-restarted-"+UUID.randomUUID(),"","",json),json,journal,Map.of(PORT,STATION),5,new BigDecimal("3")); simulator.connect();
        simulator.command(new CommandMessage(UUID.randomUUID(),view.sessionId(),STATION,PORT,"START",40,null,Instant.now(),1));
        assertThat(simulator.isCharging(PORT)).isFalse();
        assertThat(simulator.startEffects(PORT)).isEqualTo(1);
        assertThat(simulator.stopEffects(PORT)).isEqualTo(1);
    }
    @Test void stopTransitionWaitsForAnAlreadyPublishingStartAndCannotOvertakeIt() throws Exception {
        var command=sessions.start(USER,reserve("race"),40,"start");
        CountDownLatch publishing=new CountDownLatch(1),release=new CountDownLatch(1);
        doAnswer(call->{
            Object value=call.getArgument(1);
            if(value instanceof CommandMessage m && m.type().equals("START")) { publishing.countDown(); assertThat(release.await(5,TimeUnit.SECONDS)).isTrue(); }
            return call.callRealMethod();
        }).when(backendMqtt).publish(anyString(),any());
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=executor.submit(()->gateway.start(command.commandId(),PORT,40));
            assertThat(publishing.await(5,TimeUnit.SECONDS)).isTrue();
            var stop=executor.submit(()->charging.stop(USER,command.sessionId(),"stop"));
            assertThatThrownBy(()->stop.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); start.get(5,TimeUnit.SECONDS); stop.get(5,TimeUnit.SECONDS);
        } finally { release.countDown(); }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(sessions.getInternal(command.sessionId()).status()).isEqualTo(SessionStatus.COMPLETED));
        gateway.start(command.commandId(),PORT,40);
        assertThat(simulator.isCharging(PORT)).isFalse();
        assertThat(simulator.startEffects(PORT)).isEqualTo(1);
        assertThat(simulator.stopEffects(PORT)).isEqualTo(1);
    }
    @Test void outboxRetriesLostAcknowledgementWithSameCommandAndOneDeviceEffect() throws Exception {
        var dropped=new java.util.concurrent.atomic.AtomicBoolean();
        messages.drop=(topic,data)->topic.endsWith("/events") && json.readTree(data).path("type").asText().equals("START_ACK") && dropped.compareAndSet(false,true);
        var view=charging.start(USER,reserve("lost-ack"),40,"start");
        await().atMost(Duration.ofSeconds(5)).untilTrue(dropped);
        assertThat(sessions.getInternal(view.sessionId()).status()).isEqualTo(SessionStatus.START_REQUESTED);
        jdbc.update("UPDATE device_commands SET sent_at=now()-interval '10 seconds' WHERE id=?",view.commandId());
        doThrow(new IllegalStateException("connection lost")).when(backendMqtt).publish(anyString(),any());
        gateway.retryPending();
        assertThat(sessions.command(view.commandId()).status()).isEqualTo(DeviceCommandStatus.TIMED_OUT);
        doCallRealMethod().when(backendMqtt).publish(anyString(),any());
        gateway.retryPending();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(sessions.getInternal(view.sessionId()).status()).isEqualTo(SessionStatus.CHARGING));
        assertThat(simulator.startEffects(PORT)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempts FROM device_commands WHERE id=?",Integer.class,view.commandId())).isEqualTo(2);
    }
    @Test void simulatorRestartWhileChargingEntersSafeFaultUntilStopAcknowledgement() throws Exception {
        var view=start(40); simulator.close();
        simulator=new ChargerSimulator(new MqttEndpoint(brokerUrl,"sim-crash-"+UUID.randomUUID(),"","",json),json,journal,Map.of(PORT,STATION),5,new BigDecimal("3"));
        simulator.connect();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(sessions.getInternal(view.sessionId()).deviceStatus()).isEqualTo("FAULTED"));
        assertThat(simulator.isCharging(PORT)).isFalse();
        assertThatThrownBy(()->simulator.clearFault(PORT)).isInstanceOf(IllegalStateException.class);
        gateway.retryPending();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(sessions.getInternal(view.sessionId()).status()).isEqualTo(SessionStatus.COMPLETED));
        simulator.clearFault(PORT);
        assertThat(sessions.getInternal(view.sessionId()).deviceStatus()).isEqualTo("FAULTED");
    }
    @Test void simulatorProfileRunsWithoutDatabaseOrHttpServer() throws Exception {
        Path state=Files.createTempDirectory("sim-profile").resolve("journal.json");
        try(var context=new org.springframework.boot.builder.SpringApplicationBuilder(vn.edu.campuscharge.CampusChargeApplication.class)
            .profiles("simulator").run("--device.mqtt.url="+brokerUrl,"--simulator.ports="+STATION+":"+PORT,
                "--simulator.journal-path="+state,"--simulator.tick-ms=600000","--spring.datasource.url=jdbc:invalid")) {
            assertThat(context.getBeansOfType(javax.sql.DataSource.class)).isEmpty();
            assertThat(context).isNotInstanceOf(org.springframework.boot.web.context.WebServerApplicationContext.class);
            assertThat(context.getBeansOfType(ChargerSimulator.class)).hasSize(1);
            assertThat(context.getBeansOfType(ChargingRepository.class)).isEmpty();
        }
    }
    @Test void repeatsFullTelemetryUntilStopSoLossOfTheFinalUpdateCannotStrandSession() throws Exception {
        var view=start(95);
        var droppedTelemetry=new java.util.concurrent.atomic.AtomicBoolean();
        var droppedFull=new java.util.concurrent.atomic.AtomicBoolean();
        messages.drop=(topic,payload)->{
            var tree=json.readTree(payload);
            return topic.endsWith("/telemetry") && tree.path("batteryPercent").asInt()==100 && droppedTelemetry.compareAndSet(false,true) ||
                topic.endsWith("/events") && tree.path("type").asText().equals("FULL") && droppedFull.compareAndSet(false,true);
        };
        simulator.tick();
        await().atMost(Duration.ofSeconds(5)).untilTrue(droppedFull);
        assertThat(sessions.getInternal(view.sessionId()).status()).isEqualTo(SessionStatus.CHARGING);
        simulator.tick();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(sessions.getInternal(view.sessionId()).status()).isEqualTo(SessionStatus.STOP_REQUESTED));
        assertThat(simulator.isCharging(PORT)).isFalse();
    }
    @Test void scenarioCannotControlAPortUsingAnotherStationIdentity() throws Exception {
        start(40);
        var controller=new vn.edu.campuscharge.device.simulator.SimulatorScenarioController(simulator,json);
        UUID wrongStation=UUID.randomUUID();
        var scenario=new vn.edu.campuscharge.device.simulator.SimulatorScenarioController.Scenario(UUID.randomUUID(),wrongStation,PORT,"FAULT","OVERHEAT",Instant.now(),1);
        assertThatThrownBy(()->controller.accept(DeviceMessages.topic(wrongStation,PORT,"scenarios"),json.writeValueAsBytes(scenario))).isInstanceOf(IllegalArgumentException.class);
        assertThat(simulator.isCharging(PORT)).isTrue();
    }
    @Test void concurrentFaultAndStopFinalizationUseCompatibleLockOrder() throws Exception {
        var view=start(40);
        var stop=sessions.stop(USER,view.sessionId(),"stop","USER_REQUEST");
        try(var executor=Executors.newVirtualThreadPerTaskExecutor(); var connection=jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try(var lock=connection.prepareStatement("SELECT id FROM charging_sessions WHERE id=? FOR UPDATE")) { lock.setObject(1,view.sessionId()); lock.executeQuery().close(); }
            var fault=executor.submit(()->messages.event(new Event(UUID.randomUUID(),null,null,STATION,PORT,"FAULT",null,Instant.now(),1,"OVERHEAT")));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE 'SELECT id FROM charging_sessions WHERE port_id=%'",Integer.class)).isGreaterThan(0));
            try(var ack=connection.prepareStatement("UPDATE device_commands SET status='ACKNOWLEDGED',acknowledged_at=now() WHERE id=?")) { ack.setObject(1,stop.commandId()); ack.executeUpdate(); }
            try(var finish=connection.prepareStatement("CALL complete_session(?,?,0,now(),NULL)")) { finish.setObject(1,view.sessionId()); finish.setObject(2,stop.commandId()); finish.execute(); }
            connection.commit();
            assertThat(fault.get(5,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(sessions.getInternal(view.sessionId()).status()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(sessions.getInternal(view.sessionId()).deviceStatus()).isEqualTo("FAULTED");
    }
    @Test void repeatsFaultSnapshotAfterFirstFaultEventIsLostWhileHeartbeatsContinue() throws Exception {
        var view=start(40);
        var dropped=new java.util.concurrent.atomic.AtomicBoolean();
        messages.drop=(topic,payload)->topic.endsWith("/events") && json.readTree(payload).path("type").asText().equals("FAULT") && dropped.compareAndSet(false,true);
        simulator.fault(PORT,"OVERHEAT");
        await().atMost(Duration.ofSeconds(3)).untilTrue(dropped);
        simulator.tick();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(sessions.getInternal(view.sessionId()).deviceStatus()).isEqualTo("FAULTED"));
        assertThat(sessions.getInternal(view.sessionId()).status()).isEqualTo(SessionStatus.STOP_REQUESTED);
        simulator.tick();
        gateway.retryPending();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(sessions.getInternal(view.sessionId()).status()).isEqualTo(SessionStatus.COMPLETED));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incidents",Integer.class)).isEqualTo(1);
        simulator.clearFault(PORT);
    }
    @Test void restartRepublishesPersistedIdleFaultEvenWhenOriginalFaultWasLost() throws Exception {
        var dropped=new java.util.concurrent.atomic.AtomicBoolean();
        messages.drop=(topic,payload)->topic.endsWith("/events") && json.readTree(payload).path("type").asText().equals("FAULT") && dropped.compareAndSet(false,true);
        simulator.fault(PORT,"IDLE_FAULT");
        await().atMost(Duration.ofSeconds(3)).untilTrue(dropped);
        simulator.close();
        simulator=new ChargerSimulator(new MqttEndpoint(brokerUrl,"sim-fault-restart-"+UUID.randomUUID(),"","",json),json,journal,Map.of(PORT,STATION),5,new BigDecimal("3"));
        simulator.connect();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(jdbc.queryForObject("SELECT status FROM ports WHERE id=?",String.class,PORT)).isEqualTo("FAULTED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incidents",Integer.class)).isEqualTo(1);
    }
    @Test void heartbeatRefreshWhileExpiryWaitsForStationLockPreventsFalseOfflineTransition() throws Exception {
        var view=start(40);
        raceExpiry(()->messages.event(new Event(UUID.randomUUID(),null,view.sessionId(),STATION,PORT,"HEARTBEAT",null,Instant.now(),1,null)));
        assertThat(sessions.getInternal(view.sessionId()).deviceStatus()).isEqualTo("CHARGING");
        assertThat(sessions.getInternal(view.sessionId()).status()).isEqualTo(SessionStatus.CHARGING);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incidents",Integer.class)).isZero();
    }
    @Test void changedPortStateWhileExpiryWaitsIsRecheckedBeforeIncidentOrStop() throws Exception {
        var view=start(40);
        raceExpiry(()->jdbc.update("UPDATE ports SET status='DISABLED' WHERE id=?",PORT));
        assertThat(sessions.getInternal(view.sessionId()).deviceStatus()).isEqualTo("DISABLED");
        assertThat(sessions.getInternal(view.sessionId()).status()).isEqualTo(SessionStatus.CHARGING);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incidents",Integer.class)).isZero();
    }
    private void raceExpiry(Runnable refresh) throws Exception {
        jdbc.update("UPDATE ports SET last_seen_at=now()-interval '60 seconds' WHERE id=?",PORT);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor(); var connection=jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try(var lock=connection.prepareStatement("SELECT id FROM stations WHERE id=? FOR UPDATE")) { lock.setObject(1,STATION); lock.executeQuery().close(); }
            var expiry=executor.submit(()->messages.expireHeartbeats(Instant.now(),Duration.ofSeconds(10)));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE 'SELECT id FROM stations WHERE id=%'",Integer.class)).isGreaterThan(0));
            refresh.run(); connection.commit(); expiry.get(5,TimeUnit.SECONDS);
        }
    }
    @FunctionalInterface interface MessageDrop { boolean drop(String topic,byte[] payload) throws java.io.IOException; }
    static class TestDeviceMessageHandler extends DeviceMessageHandler {
        volatile MessageDrop drop=(topic,payload)->false;
        TestDeviceMessageHandler(org.springframework.jdbc.core.JdbcTemplate jdbc,ChargingRepository sessions,com.fasterxml.jackson.databind.ObjectMapper json,org.springframework.transaction.PlatformTransactionManager manager) { super(jdbc,sessions,json,manager); }
        @Override public void accept(String topic,byte[] payload) {
            try { if(!drop.drop(topic,payload)) super.accept(topic,payload); }
            catch(java.io.IOException malformed) { throw new IllegalArgumentException(malformed); }
        }
    }
    @TestConfiguration static class MessageConfiguration {
        @Bean @Primary TestDeviceMessageHandler testDeviceMessages(org.springframework.jdbc.core.JdbcTemplate jdbc,ChargingRepository sessions,com.fasterxml.jackson.databind.ObjectMapper json,org.springframework.transaction.PlatformTransactionManager manager) {
            return new TestDeviceMessageHandler(jdbc,sessions,json,manager);
        }
    }
}
