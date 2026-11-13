package vn.edu.campuscharge.identity;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.edu.campuscharge.api.ApiTestSupport;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc(addFilters=true)
public abstract class SecurityTestSupport extends ApiTestSupport {
    public static final UUID OPERATOR=UUID.fromString("00000000-0000-0000-0000-000000000003");
    public static final UUID ADMIN=UUID.fromString("00000000-0000-0000-0000-000000000004");
    public static final String PASSWORD="Demo12345!";
    @BeforeEach void securityUsers() {
        String hash=new BCryptPasswordEncoder().encode(PASSWORD);
        jdbc.update("UPDATE users SET password_hash=?",hash);
        jdbc.update("INSERT INTO users(id,email,password_hash,role) VALUES (?,? ,?,'OPERATOR'),(?,?,?,'ADMIN')",OPERATOR,"operator@test",hash,ADMIN,"admin@test",hash);
        jdbc.update("UPDATE ports SET last_seen_at=now()");
    }
    protected final class Browser {
        public MockHttpSession session;
        public String csrf;
        public Browser() throws Exception { refresh(); }
        public void refresh() throws Exception {
            var request=get("/api/auth/csrf"); if(session!=null) request.session(session);
            var result=mvc.perform(request).andExpect(status().isOk()).andReturn();
            session=(MockHttpSession)result.getRequest().getSession(false);
            csrf=json.readTree(result.getResponse().getContentAsString()).get("token").asText();
        }
        public MockHttpServletRequestBuilder mutation(MockHttpServletRequestBuilder request) { return request.session(session).header("X-CSRF-TOKEN",csrf); }
        public void login(String email) throws Exception {
            var result=mvc.perform(mutation(post("/api/auth/login")).contentType("application/x-www-form-urlencoded").param("email",email).param("password",PASSWORD))
                .andExpect(status().isOk()).andReturn();
            session=(MockHttpSession)result.getRequest().getSession(false); refresh();
        }
    }
    protected Browser browser(String email) throws Exception { var b=new Browser(); b.login(email); return b; }
}
