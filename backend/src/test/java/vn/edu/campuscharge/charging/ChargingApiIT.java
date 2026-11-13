package vn.edu.campuscharge.charging;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.edu.campuscharge.api.ApiTestSupport;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

class ChargingApiIT extends ApiTestSupport {
    @Test void rejectsOutOfRangeOrMissingInitialBattery() throws Exception {
        for (String body : java.util.List.of("{\"initialBatteryPercent\":100}","{\"initialBatteryPercent\":-1}","{\"initialBatteryPercent\":35.5}","{}"))
            mvc.perform(post("/api/bookings/"+UUID.randomUUID()+"/start").with(authentication(owner())).with(csrf()).header("Idempotency-Key","start")
                .contentType("application/json").content(body)).andExpect(status().isBadRequest());
    }
    @Test void startAndStopReplaysKeepOneSessionAndWaitForAcknowledgements() throws Exception {
        UUID booking=reserve("reserve");
        String body=start(booking,"start",35).andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("START_REQUESTED")).andExpect(jsonPath("$.deliveryPending").value(true))
            .andReturn().getResponse().getContentAsString();
        String id=json.readTree(body).get("sessionId").asText();
        String startCommand=json.readTree(body).get("commandId").asText();
        start(booking,"start",35).andExpect(status().isAccepted()).andExpect(jsonPath("$.sessionId").value(id))
            .andExpect(jsonPath("$.commandId").value(startCommand));
        start(booking,"start",36).andExpect(status().isConflict());
        start(booking,"new-start",35).andExpect(status().isConflict());
        mvc.perform(post("/api/bookings/"+booking+"/cancel").with(authentication(owner())).with(csrf()).header("Idempotency-Key","cancel"))
            .andExpect(status().isConflict());
        String stopped=mvc.perform(post("/api/sessions/"+id+"/stop").with(authentication(owner())).with(csrf()).header("Idempotency-Key","stop"))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("STOP_REQUESTED"))
            .andExpect(jsonPath("$.totalFee").isEmpty()).andReturn().getResponse().getContentAsString();
        String stopCommand=json.readTree(stopped).get("commandId").asText();
        mvc.perform(post("/api/sessions/"+id+"/stop").with(authentication(owner())).with(csrf()).header("Idempotency-Key","stop"))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.commandId").value(stopCommand));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM charging_sessions",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM device_commands",Integer.class)).isEqualTo(2);
        mvc.perform(get("/api/sessions/"+id).with(authentication(other()))).andExpect(status().isForbidden());
        mvc.perform(post("/api/sessions/"+id+"/stop").with(authentication(other())).with(csrf()).header("Idempotency-Key","steal"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/sessions").with(authentication(owner()))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/sessions/"+id)).andExpect(status().isUnauthorized());
    }
    @Test void preventsStartOutsideBookingWindowOrOnFaultedPort() throws Exception {
        UUID booking=reserve("reserve");
        jdbc.update("UPDATE ports SET status='FAULTED' WHERE id=?",PORT);
        start(booking,"start",0).andExpect(status().isConflict());
        jdbc.update("UPDATE ports SET status='AVAILABLE' WHERE id=?",PORT);
        jdbc.update("UPDATE bookings SET start_at='2031-01-01',end_at='2031-01-02' WHERE id=?",booking);
        start(booking,"start",0).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM charging_sessions",Integer.class)).isZero();
    }
    private org.springframework.test.web.servlet.ResultActions start(UUID booking,String key,int battery) throws Exception {
        return mvc.perform(post("/api/bookings/"+booking+"/start").with(authentication(owner())).with(csrf()).header("Idempotency-Key",key)
            .contentType("application/json").content("{\"initialBatteryPercent\":"+battery+"}"));
    }
}
