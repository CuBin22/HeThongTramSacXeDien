package vn.edu.campuscharge.device.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import vn.edu.campuscharge.device.MqttEndpoint;

@Configuration(proxyBeanMethods=false)
@Profile("simulator")
public class SimulatorConfiguration {
    @Bean(destroyMethod="close") ChargerSimulator chargerSimulator(ObjectMapper json,
        @Value("${device.mqtt.url:tcp://localhost:1883}") String url,@Value("${device.mqtt.username:}") String username,@Value("${device.mqtt.password:}") String password,
        @Value("${simulator.journal-path:./data/simulator.json}") String journal,@Value("${simulator.ports:}") String definitions,
        @Value("${simulator.battery-step-percent:5}") int step) {
        Map<UUID,UUID> ports=new LinkedHashMap<>();
        if(!definitions.isBlank()) for(String definition:definitions.split(",")) {
            String[] pair=definition.trim().split(":");
            if(pair.length!=2) throw new IllegalArgumentException("SIMULATOR_PORTS format is stationUUID:portUUID separated by commas");
            ports.put(UUID.fromString(pair[1]),UUID.fromString(pair[0]));
        }
        var simulator=new ChargerSimulator(new MqttEndpoint(url,"campus-simulator-"+UUID.randomUUID(),username,password,json),json,Path.of(journal),ports,step);
        simulator.connect(); return simulator;
    }
    @Bean SimulatorTicker simulatorTicker(ChargerSimulator simulator,JdbcTemplate jdbc) { return new SimulatorTicker(simulator,jdbc); }
    static class SimulatorTicker {
        private static final Logger log=LoggerFactory.getLogger(SimulatorTicker.class);
        final ChargerSimulator simulator;
        final JdbcTemplate jdbc;
        SimulatorTicker(ChargerSimulator simulator,JdbcTemplate jdbc) { this.simulator=simulator; this.jdbc=jdbc; }
        @Scheduled(fixedDelayString="${simulator.tick-ms:1000}") public void tick() {
            try {
                var registered=new LinkedHashMap<UUID,UUID>();
                jdbc.query("SELECT id,station_id FROM ports ORDER BY id",(r,n)->Map.entry(r.getObject("id",UUID.class),r.getObject("station_id",UUID.class)))
                    .forEach(entry->registered.put(entry.getKey(),entry.getValue()));
                simulator.syncPorts(registered);
            } catch(DataAccessException unavailable) { log.warn("Could not refresh simulator ports from PostgreSQL",unavailable); }
            simulator.tick();
        }
    }
}
