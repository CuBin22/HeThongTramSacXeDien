package vn.edu.campuscharge.device;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class DeviceMessages {
    private DeviceMessages() {}
    public record CommandMessage(UUID commandId,UUID sessionId,UUID stationId,UUID portId,String type,
        Integer initialBatteryPercent,BigDecimal batteryCapacityKwh,String reason,Instant timestamp,int schemaVersion) {
        public CommandMessage(UUID commandId,UUID sessionId,UUID stationId,UUID portId,String type,
                Integer initialBatteryPercent,String reason,Instant timestamp,int schemaVersion) {
            this(commandId,sessionId,stationId,portId,type,initialBatteryPercent,new BigDecimal("3"),reason,timestamp,schemaVersion);
        }
    }
    public record Event(UUID eventId,UUID commandId,UUID sessionId,UUID stationId,UUID portId,String type,
        BigDecimal energyKwh,Instant timestamp,int schemaVersion,String description) {}
    public record Telemetry(UUID eventId,UUID sessionId,UUID stationId,UUID portId,Integer batteryPercent,
        BigDecimal energyKwh,Instant timestamp,int schemaVersion) {}
    public static String topic(UUID station,UUID port,String kind) { return "campus/"+station+"/"+port+"/"+kind; }
    public static void topicMatches(String topic,UUID station,UUID port,String kind) {
        if(station==null || port==null || !topic(station,port,kind).equals(topic)) throw new IllegalArgumentException("MQTT topic does not match message identity");
    }
}
