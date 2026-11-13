package vn.edu.campuscharge.api;

import java.util.UUID;
import java.time.Instant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest
@AutoConfigureMockMvc
public abstract class ApiTestSupport {
    public static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    public static final UUID STATION = UUID.fromString("00000000-0000-0000-0000-000000000010");
    public static final UUID PORT = UUID.fromString("00000000-0000-0000-0000-000000000020");
    public static final UUID PORT2 = UUID.fromString("00000000-0000-0000-0000-000000000021");
    static PostgreSQLContainer<?> postgres;
    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ObjectMapper json;
    protected static UsernamePasswordAuthenticationToken owner() { return new UsernamePasswordAuthenticationToken(USER.toString(), "", java.util.List.of()); }
    protected static UsernamePasswordAuthenticationToken other() { return new UsernamePasswordAuthenticationToken(OTHER.toString(), "", java.util.List.of()); }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        String url = System.getenv("DB_TEST_URL");
        String user = System.getenv().getOrDefault("DB_TEST_USERNAME", "campus_test");
        String password = System.getenv().getOrDefault("DB_TEST_PASSWORD", "");
        if (url == null || url.isBlank()) {
            postgres = new PostgreSQLContainer<>("postgres:16-alpine"); postgres.start();
            url = postgres.getJdbcUrl(); user = postgres.getUsername(); password = postgres.getPassword();
        }
        final String u=url, n=user, p=password;
        r.add("spring.datasource.url", () -> u); r.add("spring.datasource.username", () -> n); r.add("spring.datasource.password", () -> p);
    }
    @BeforeEach void seed() {
        jdbc.execute("TRUNCATE users,stations,tariffs,status_audit RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO users(id,email,password_hash) VALUES (?,?,'test'),(?,?,'test')", USER,"one@test",OTHER,"two@test");
        jdbc.update("INSERT INTO stations(id,name) VALUES (?,'Campus')", STATION);
        jdbc.update("INSERT INTO ports(id,station_id) VALUES (?,?)", PORT, STATION);
        jdbc.execute("INSERT INTO tariffs(price_per_kwh) VALUES (3000)");
    }
    protected UUID reserve(String key) throws Exception {
        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/bookings")
                .with(authentication(owner())).with(csrf()).header("Idempotency-Key", key).contentType("application/json").content(bookingJson()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("bookingId").asText());
    }
    protected String bookingJson() {
        Instant now=Instant.now();
        return "{\"stationId\":\""+STATION+"\",\"startAt\":\""+now.minusSeconds(60)+"\",\"endAt\":\""+now.plusSeconds(3*60*60)+"\"}";
    }
}
