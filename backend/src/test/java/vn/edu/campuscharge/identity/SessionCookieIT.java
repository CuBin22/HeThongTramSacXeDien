package vn.edu.campuscharge.identity;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import vn.edu.campuscharge.api.ApiTestSupport;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.servlet.session.cookie.secure=true")
class SessionCookieIT extends ApiTestSupport {
    @LocalServerPort int port;
    @Test void sessionCookieUsesHttpOnlySameSiteAndConfiguredSecureFlag() throws Exception {
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().allValues("set-cookie")).anySatisfy(cookie->assertThat(cookie).contains("JSESSIONID=","HttpOnly","Secure","SameSite=Lax"));
    }
}
