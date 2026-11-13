package vn.edu.campuscharge.device.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import vn.edu.campuscharge.device.DeviceMessages;

/** Demo-only MQTT scenario control, not an HTTP API. */
public class SimulatorScenarioController {
    private static final BigDecimal OVERHEAT_THRESHOLD_C = new BigDecimal("70.0");
    public record Scenario(UUID eventId,UUID stationId,UUID portId,String action,String description,Instant timestamp,int schemaVersion,BigDecimal temperatureC) {
        public Scenario(UUID eventId,UUID stationId,UUID portId,String action,String description,Instant timestamp,int schemaVersion) {
            this(eventId,stationId,portId,action,description,timestamp,schemaVersion,null);
        }
    }
    private final ChargerSimulator simulator;
    private final ObjectMapper json;
    public SimulatorScenarioController(ChargerSimulator simulator,ObjectMapper json) { this.simulator=simulator; this.json=json; }
    public void accept(String topic,byte[] payload) {
        try {
            Scenario scenario=json.readValue(payload,Scenario.class);
            DeviceMessages.topicMatches(topic,scenario.stationId(),scenario.portId(),"scenarios");
            simulator.requireIdentity(scenario.stationId(),scenario.portId());
            if(scenario.eventId()==null || scenario.timestamp()==null || scenario.schemaVersion()!=1 || scenario.timestamp().isAfter(Instant.now().plusSeconds(5))) throw new IllegalArgumentException("Invalid scenario envelope");
            switch(scenario.action()) {
                case "FAULT" -> simulator.fault(scenario.portId(),scenario.description());
                case "OVERHEAT_WHILE_CHARGING" -> {
                    if(!simulator.isCharging(scenario.portId())) throw new IllegalStateException("Overheat demo requires an active charging session on this port");
                    if(scenario.temperatureC()==null || scenario.temperatureC().compareTo(OVERHEAT_THRESHOLD_C)<0)
                        throw new IllegalArgumentException("Overheat temperature must be at least "+OVERHEAT_THRESHOLD_C+" C");
                    String description="OVERHEAT: measured "+scenario.temperatureC().stripTrailingZeros().toPlainString()+" C; threshold "+OVERHEAT_THRESHOLD_C+" C";
                    if(scenario.description()!=null && !scenario.description().isBlank()) description+="; "+scenario.description();
                    simulator.fault(scenario.portId(),description);
                }
                case "CLEAR_FAULT" -> simulator.recover(scenario.eventId(),scenario.portId(),scenario.timestamp());
                case "DISCONNECT_PORT" -> simulator.disconnectPort(scenario.portId(),true);
                case "RECONNECT_PORT" -> simulator.disconnectPort(scenario.portId(),false);
                case "PAUSE_HEARTBEATS" -> simulator.pauseHeartbeats(true);
                case "RESUME_HEARTBEATS" -> simulator.pauseHeartbeats(false);
                default -> throw new IllegalArgumentException("Unknown simulator scenario");
            }
        } catch(java.io.IOException malformed) { throw new IllegalArgumentException("Malformed simulator scenario",malformed); }
    }
}
