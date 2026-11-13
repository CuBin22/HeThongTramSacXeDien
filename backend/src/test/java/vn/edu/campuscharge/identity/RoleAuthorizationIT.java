package vn.edu.campuscharge.identity;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RoleAuthorizationIT extends SecurityTestSupport {
    @Test void registrationHashesPasswordAndNeverAcceptsRoleAssignment() throws Exception {
        var b=new Browser();
        mvc.perform(b.mutation(post("/api/auth/register")).contentType("application/json").content("{\"email\":\" Student@Campus.test \",\"password\":\"Student123!\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.email").value("student@campus.test")).andExpect(jsonPath("$.role").value("USER"));
        String hash=jdbc.queryForObject("SELECT password_hash FROM users WHERE email='student@campus.test'",String.class);
        assertThat(hash).isNotEqualTo("Student123!"); assertThat(new BCryptPasswordEncoder().matches("Student123!",hash)).isTrue();
        mvc.perform(b.mutation(post("/api/auth/register")).contentType("application/json").content("{\"email\":\"evil@test\",\"password\":\"Student123!\",\"role\":\"ADMIN\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(b.mutation(post("/api/auth/register")).contentType("application/json").content("{\"email\":\"bad\",\"password\":\"short\"}"))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email='evil@test'",Integer.class)).isZero();
    }
    @Test void loginUsesUuidSessionIdentityAndCsrfAndLogoutInvalidatesIt() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        var b=new Browser();
        mvc.perform(post("/api/auth/login").session(b.session).contentType("application/x-www-form-urlencoded").param("email","one@test").param("password",PASSWORD))
            .andExpect(status().isForbidden());
        mvc.perform(b.mutation(post("/api/auth/login")).contentType("application/x-www-form-urlencoded").param("email","one@test").param("password","wrong"))
            .andExpect(status().isUnauthorized());
        b.login("one@test");
        mvc.perform(get("/api/auth/me").session(b.session)).andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(USER.toString()));
        mvc.perform(post("/api/bookings").session(b.session).header("Idempotency-Key","csrf").contentType("application/json").content(bookingJson()))
            .andExpect(status().isForbidden());
        mvc.perform(b.mutation(post("/api/auth/logout"))).andExpect(status().isNoContent());
        assertThat(b.session.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }
    @Test void roleMatrixSeparatesUserOperatorAndAdminRoutes() throws Exception {
        var user=browser("one@test"); var operator=browser("operator@test"); var admin=browser("admin@test");
        mvc.perform(get("/api/stations")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/stations").session(user.session)).andExpect(status().isOk());
        mvc.perform(get("/api/incidents").session(user.session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/incidents").session(operator.session)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/users").session(operator.session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").session(admin.session)).andExpect(status().isOk()).andExpect(jsonPath("$[0].passwordHash").doesNotExist());
        mvc.perform(user.mutation(post("/api/admin/stations")).contentType("application/json").content("{\"name\":\"Other\",\"connectorType\":\"STANDARD\"}"))
            .andExpect(status().isForbidden());
    }
    @Test void authenticatedUsersCannotReadOrStartAnotherUsersResources() throws Exception {
        var owner=browser("one@test"); var other=browser("two@test");
        String body=mvc.perform(owner.mutation(post("/api/bookings")).header("Idempotency-Key","owned").contentType("application/json").content(bookingJson()))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id=json.readTree(body).get("bookingId").asText();
        mvc.perform(get("/api/bookings/"+id).session(other.session)).andExpect(status().isForbidden());
        mvc.perform(other.mutation(post("/api/bookings/"+id+"/start")).header("Idempotency-Key","steal").contentType("application/json").content("{\"initialBatteryPercent\":40}"))
            .andExpect(status().isForbidden());
        String started=mvc.perform(owner.mutation(post("/api/bookings/"+id+"/start")).header("Idempotency-Key","start").contentType("application/json").content("{\"initialBatteryPercent\":40}"))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String session=json.readTree(started).get("sessionId").asText();
        mvc.perform(get("/api/sessions/"+session).session(other.session)).andExpect(status().isForbidden());
    }
    @Test void adminChangesAreEnforcedForExistingSessionsAndCannotEscalateFromUser() throws Exception {
        var user=browser("one@test"); var admin=browser("admin@test");
        mvc.perform(user.mutation(put("/api/admin/users/"+USER)).contentType("application/json").content("{\"role\":\"ADMIN\",\"enabled\":true}"))
            .andExpect(status().isForbidden());
        mvc.perform(admin.mutation(put("/api/admin/users/"+USER)).contentType("application/json").content("{\"role\":\"OPERATOR\",\"enabled\":true}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/incidents").session(user.session)).andExpect(status().isOk());
        mvc.perform(admin.mutation(put("/api/admin/users/"+USER)).contentType("application/json").content("{\"role\":\"USER\",\"enabled\":false}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/stations").session(user.session)).andExpect(status().isForbidden());
    }
    @Test void adminManagesStationsPortsTariffsAndViewsReports() throws Exception {
        var admin=browser("admin@test");
        String station=mvc.perform(admin.mutation(post("/api/admin/stations")).contentType("application/json").content("{\"name\":\"Library\",\"connectorType\":\"STANDARD\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id=json.readTree(station).get("stationId").asText();
        mvc.perform(admin.mutation(put("/api/admin/stations/"+id)).contentType("application/json").content("{\"name\":\"Library A\",\"active\":false}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        mvc.perform(admin.mutation(post("/api/admin/stations/"+id+"/ports")).contentType("application/json").content("{}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("OFFLINE"));
        mvc.perform(admin.mutation(post("/api/admin/tariffs")).contentType("application/json").content("{\"stationId\":\""+id+"\",\"pricePerKwh\":3500}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.pricePerKwh").value(3500));
        mvc.perform(admin.mutation(post("/api/admin/tariffs")).contentType("application/json").content("{\"pricePerKwh\":-1}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/tariffs").session(admin.session)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/admin/reports").session(admin.session)).andExpect(status().isOk()).andExpect(jsonPath("$.completedSessions").value(0)).andExpect(jsonPath("$.totalEnergyKwh").value(0));
    }
}
