package vn.edu.campuscharge.device;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.moquette.broker.Server;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class MqttEndpointTest {
    @Test void initialConnectionFailureRecoversWithoutAnyLocalPublishOrPendingCommand() throws Exception {
        int port; try(var socket=new ServerSocket(0)) { port=socket.getLocalPort(); }
        String url="tcp://127.0.0.1:"+port;
        var json=JsonMapper.builder().build();
        var received=new CompletableFuture<String>();
        Server broker=new Server();
        try(var subscriber=new MqttEndpoint(url,"idle-recovery-subscriber","","",json);
            var publisher=new MqttEndpoint(url,"external-heartbeat-publisher","","",json)) {
            assertThatThrownBy(()->subscriber.start(new String[]{"test/heartbeat"},(topic,data)->received.complete(topic))).isInstanceOf(IllegalStateException.class);
            Properties config=new Properties(); config.setProperty("port",Integer.toString(port)); config.setProperty("host","127.0.0.1");
            config.setProperty("allow_anonymous","true"); config.setProperty("persistence_enabled","false"); config.setProperty("telemetry_enabled","false"); broker.startServer(config);
            await().atMost(Duration.ofSeconds(4)).untilAsserted(()-> {
                publisher.publish("test/heartbeat",Map.of("eventId",UUID.randomUUID()));
                assertThat(received).isCompletedWithValue("test/heartbeat");
            });
        } finally { broker.stopServer(); }
    }
    @Test void initialConnectionFailureCanRecoverAndSubscribeWithoutRestartingApplication() throws Exception {
        int port; try(var socket=new ServerSocket(0)) { port=socket.getLocalPort(); }
        String url="tcp://127.0.0.1:"+port;
        var json=JsonMapper.builder().build();
        var received=new CompletableFuture<String>();
        Server broker=new Server();
        try(var endpoint=new MqttEndpoint(url,"recovery-subscriber","","",json)) {
            assertThatThrownBy(()->endpoint.start(new String[]{"test/events"},(topic,data)->received.complete(topic))).isInstanceOf(IllegalStateException.class);
            Properties config=new Properties(); config.setProperty("port",Integer.toString(port)); config.setProperty("host","127.0.0.1");
            config.setProperty("allow_anonymous","true"); config.setProperty("persistence_enabled","false"); config.setProperty("telemetry_enabled","false"); broker.startServer(config);
            endpoint.publish("test/events",Map.of("eventId",UUID.randomUUID()));
            await().atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(received).isCompletedWithValue("test/events"));
        } finally { broker.stopServer(); }
    }
}
