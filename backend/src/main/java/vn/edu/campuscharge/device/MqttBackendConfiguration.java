package vn.edu.campuscharge.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import vn.edu.campuscharge.charging.ChargingRepository;

@Configuration(proxyBeanMethods=false)
@Profile("!simulator")
@ConditionalOnProperty(name="device.mqtt.enabled",havingValue="true")
public class MqttBackendConfiguration {
    @Bean(destroyMethod="close") MqttEndpoint backendMqtt(@Value("${device.mqtt.url:tcp://localhost:1883}") String url,
        @Value("${device.mqtt.username:}") String username,@Value("${device.mqtt.password:}") String password,
        ObjectMapper json,DeviceMessageHandler messages) {
        var endpoint=new MqttEndpoint(url,"campus-backend-"+UUID.randomUUID(),username,password,json);
        try { endpoint.start(new String[]{"campus/+/+/events","campus/+/+/telemetry"},messages::accept); }
        catch(IllegalStateException unavailable) { LoggerFactory.getLogger(MqttBackendConfiguration.class).warn("MQTT unavailable at startup; connection maintenance will retry"); }
        return endpoint;
    }
    @Bean MqttDeviceGateway mqttDeviceGateway(JdbcTemplate jdbc,ChargingRepository sessions,PlatformTransactionManager manager,
        MqttEndpoint backendMqtt,@Value("${device.ack-timeout-seconds:5}") long seconds) {
        return new MqttDeviceGateway(jdbc,sessions,manager,backendMqtt,Duration.ofSeconds(seconds));
    }
    @Bean
    @ConditionalOnProperty(name="device.scheduling.enabled",havingValue="true",matchIfMissing=true)
    DeviceScheduler deviceScheduler(MqttDeviceGateway gateway,DeviceMessageHandler messages,DeviceRecoveryService recovery,@Value("${device.heartbeat-timeout-seconds:15}") long seconds) {
        return new DeviceScheduler(gateway,messages,recovery,Duration.ofSeconds(seconds));
    }
    static class DeviceScheduler {
        private final MqttDeviceGateway gateway;
        private final DeviceMessageHandler messages;
        private final DeviceRecoveryService recovery;
        private final Duration timeout;
        DeviceScheduler(MqttDeviceGateway gateway,DeviceMessageHandler messages,DeviceRecoveryService recovery,Duration timeout) { this.gateway=gateway; this.messages=messages; this.recovery=recovery; this.timeout=timeout; }
        @Scheduled(fixedDelayString="${device.outbox-interval-ms:1000}") public void retry() { gateway.retryPending(); recovery.retryPending(); }
        @Scheduled(fixedDelayString="${device.heartbeat-check-ms:1000}") public void check() { messages.expireHeartbeats(Instant.now(),timeout); }
    }
}
