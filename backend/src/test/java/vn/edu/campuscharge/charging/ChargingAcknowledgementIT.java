package vn.edu.campuscharge.charging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.edu.campuscharge.api.ApiTestSupport;
import vn.edu.campuscharge.device.DeviceGateway;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@Import(ChargingAcknowledgementIT.GatewayConfiguration.class)
class ChargingAcknowledgementIT extends ApiTestSupport {
    @Autowired ChargingService service;
    @Autowired RecordingGateway gateway;
    @BeforeEach void resetGateway() { gateway.accepted.clear(); gateway.offline=false; }

    @Test void persistedCommandIsSentBeforeAcknowledgementAndFinalFeeIsFrozenOnce() throws Exception {
        UUID booking=reserve("reservation");
        var started=service.start(USER,booking,20,"start");
        assertThat(started.status()).isEqualTo(SessionStatus.START_REQUESTED);
        assertThat(started.commandStatus()).isEqualTo(DeviceCommandStatus.SENT);
        assertThat(gateway.accepted).containsExactly(started.commandId());
        service.start(USER,booking,20,"start");
        assertThat(gateway.accepted).hasSize(1);
        assertThatThrownBy(()->service.acknowledgeStart(started.commandId(),PORT2,Instant.now())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        service.acknowledgeStart(started.commandId(),PORT,Instant.now());
        assertThat(service.get(USER,started.sessionId()).status()).isEqualTo(SessionStatus.CHARGING);
        var stopped=service.stop(USER,started.sessionId(),"stop");
        assertThat(stopped.status()).isEqualTo(SessionStatus.STOP_REQUESTED);
        assertThat(stopped.totalFee()).isNull();
        assertThatThrownBy(()->service.acknowledgeStop(started.commandId(),PORT,new BigDecimal("1.25"),Instant.now()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        service.acknowledgeStop(stopped.commandId(),PORT,new BigDecimal("1.25"),Instant.now());
        service.acknowledgeStop(stopped.commandId(),PORT,new BigDecimal("9.99"),Instant.now());
        var completed=service.get(USER,started.sessionId());
        assertThat(completed.status()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(completed.totalFee()).isEqualByComparingTo("3750");
        assertThat(completed.energyKwh()).isEqualByComparingTo("1.25");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM status_audit WHERE entity_table='charging_sessions' AND new_status='COMPLETED'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,booking)).isEqualTo("COMPLETED");
        mvc.perform(get("/api/sessions/"+started.sessionId()).with(authentication(owner()))).andExpect(status().isOk())
            .andExpect(jsonPath("$.totalFee").value(3750));
    }
    @Test void unavailableGatewayLeavesDurablePendingCommandAndRetryUsesSameId() throws Exception {
        UUID booking=reserve("reservation");
        gateway.offline=true;
        var pending=service.start(USER,booking,0,"start");
        assertThat(pending.status()).isEqualTo(SessionStatus.START_REQUESTED);
        assertThat(pending.deliveryPending()).isTrue();
        gateway.offline=false;
        var retried=service.start(USER,booking,0,"start");
        assertThat(retried.commandId()).isEqualTo(pending.commandId());
        assertThat(retried.commandStatus()).isEqualTo(DeviceCommandStatus.SENT);
        assertThat(gateway.accepted).containsExactly(pending.commandId());
    }
    @Test void lateStartAcknowledgementCannotReopenAStopRequestedSession() throws Exception {
        UUID booking=reserve("reservation");
        gateway.offline=true;
        var start=service.start(USER,booking,20,"start");
        var stop=service.stop(USER,start.sessionId(),"stop");
        gateway.offline=false;
        service.start(USER,booking,20,"start");
        assertThat(gateway.accepted).isEmpty();
        service.acknowledgeStart(start.commandId(),PORT,Instant.now());
        assertThat(service.get(USER,start.sessionId()).status()).isEqualTo(SessionStatus.STOP_REQUESTED);
        service.acknowledgeStop(stop.commandId(),PORT,BigDecimal.ZERO,Instant.now());
        assertThat(service.get(USER,start.sessionId()).totalFee()).isEqualByComparingTo("0");
    }
    @TestConfiguration static class GatewayConfiguration {
        @Bean RecordingGateway gateway(JdbcTemplate jdbc) { return new RecordingGateway(jdbc); }
    }
    static class RecordingGateway implements DeviceGateway {
        final JdbcTemplate jdbc;
        final List<UUID> accepted=new ArrayList<>();
        boolean offline;
        RecordingGateway(JdbcTemplate jdbc) { this.jdbc=jdbc; }
        private AcceptedCommand accept(UUID id) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).as("delivery runs after commit").isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM device_commands WHERE id=?",Integer.class,id)).isEqualTo(1);
            if(offline) throw new IllegalStateException("broker unavailable");
            accepted.add(id);
            return new AcceptedCommand(id);
        }
        public AcceptedCommand start(UUID id,UUID port,int battery) { return accept(id); }
        public AcceptedCommand stop(UUID id,UUID port,String reason) { return accept(id); }
    }
}
