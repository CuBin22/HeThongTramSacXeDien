package vn.edu.campuscharge.identity;

import java.net.*;
import java.net.http.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class EmbeddedApiErrorsIT extends SecurityTestSupport {
    @LocalServerPort int port;
    @Test void embeddedServerPreservesControllerErrorStatusesDuringErrorRedispatch() throws Exception {
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        String token=token(client);
        var login=HttpRequest.newBuilder(uri("/api/auth/login")).header("Content-Type","application/x-www-form-urlencoded").header("X-CSRF-TOKEN",token)
            .POST(HttpRequest.BodyPublishers.ofString("email=operator%40test&password=Demo12345%21")).build();
        assertThat(client.send(login,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200); token=token(client);
        assertThat(client.send(HttpRequest.newBuilder(uri("/api/bookings/"+UUID.randomUUID())).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
        var invalid=HttpRequest.newBuilder(uri("/api/bookings/"+UUID.randomUUID()+"/start")).header("Content-Type","application/json").header("X-CSRF-TOKEN",token).header("Idempotency-Key","invalid")
            .POST(HttpRequest.BodyPublishers.ofString("{\"initialBatteryPercent\":100}")).build();
        assertThat(client.send(invalid,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        jdbc.update("UPDATE ports SET status='DISABLED' WHERE id=?",PORT);
        var conflict=HttpRequest.newBuilder(uri("/api/operator/ports/"+PORT+"/restore")).header("X-CSRF-TOKEN",token).POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThat(client.send(conflict,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);
        assertThat(client.send(HttpRequest.newBuilder(uri("/error")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }
    private URI uri(String path) { return URI.create("http://localhost:"+port+path); }
    private String token(HttpClient client) throws Exception {
        var response=client.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200); return json.readTree(response.body()).get("token").asText();
    }
}
