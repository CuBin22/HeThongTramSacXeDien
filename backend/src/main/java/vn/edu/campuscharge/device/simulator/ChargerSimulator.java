package vn.edu.campuscharge.device.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import vn.edu.campuscharge.device.*;
import vn.edu.campuscharge.device.DeviceMessages.*;

/** Software model only. Session energy follows the battery capacity saved with the vehicle. */
public class ChargerSimulator implements AutoCloseable {
    public record Port(UUID stationId,UUID sessionId,boolean charging,boolean faulted,int battery,BigDecimal energy,int starts,int stops,BigDecimal batteryCapacityKwh) {
        public Port(UUID stationId,UUID sessionId,boolean charging,boolean faulted,int battery,BigDecimal energy,int starts,int stops) {
            this(stationId,sessionId,charging,faulted,battery,energy,starts,stops,null);
        }
    }
    public record Processed(CommandMessage command,Event response) {}
    public record Recovered(Instant requestedAt,Event response) {}
    public record Journal(Map<UUID,Port> ports,Map<UUID,Processed> processed,Map<UUID,BigDecimal> stopped,Map<UUID,Recovered> recoveries,Map<UUID,Instant> faultedAt) {}
    private final MqttEndpoint mqtt;
    private final ObjectMapper json;
    private final Path journalPath;
    private final int batteryStep;
    private final Map<UUID,Port> ports=new LinkedHashMap<>();
    // ponytail: demo journal grows with commands; rotate archived sessions if long-running deployment is needed.
    private final Map<UUID,Processed> processed=new LinkedHashMap<>();
    private final Map<UUID,BigDecimal> stopped=new LinkedHashMap<>();
    private final Map<UUID,Recovered> recoveries=new LinkedHashMap<>();
    private final Map<UUID,Instant> faultedAt=new LinkedHashMap<>();
    private final Set<UUID> rebootFaults=new HashSet<>();
    private boolean heartbeatsPaused;
    private final Set<UUID> disconnectedPorts=new HashSet<>();
    private Instant lastTimestamp=Instant.EPOCH;
    public ChargerSimulator(MqttEndpoint mqtt,ObjectMapper json,Path journalPath,Map<UUID,UUID> configuredPorts,int batteryStep) {
        if(batteryStep<1 || batteryStep>100) throw new IllegalArgumentException("Invalid simulator battery step");
        this.mqtt=mqtt; this.json=json; this.journalPath=journalPath.toAbsolutePath(); this.batteryStep=batteryStep;
        if(Files.exists(this.journalPath)) try {
            Journal saved=json.readValue(this.journalPath.toFile(),Journal.class);
            ports.putAll(saved.ports()); processed.putAll(saved.processed()); stopped.putAll(saved.stopped());
            if(saved.recoveries()!=null) recoveries.putAll(saved.recoveries());
            if(saved.faultedAt()!=null) faultedAt.putAll(saved.faultedAt());
            ports.replaceAll((id,p)-> { if(p.charging()) { rebootFaults.add(id); faultedAt.put(id,Instant.now()); return new Port(p.stationId(),p.sessionId(),false,true,p.battery(),p.energy(),p.starts(),p.stops(),p.batteryCapacityKwh()); } return p; });
            ports.forEach((id,p)-> { if(p.faulted()) faultedAt.putIfAbsent(id,Instant.now()); });
        } catch(Exception broken) { throw new IllegalStateException("Simulator journal cannot be read safely",broken); }
        configuredPorts.forEach((id,station)-> {
            Port old=ports.putIfAbsent(id,new Port(station,null,false,false,0,BigDecimal.ZERO,0,0));
            if(old!=null && !old.stationId().equals(station)) throw new IllegalArgumentException("Simulator port station changed");
        });
        save();
    }
    /** Backwards-compatible test constructor; capacity now comes from the vehicle snapshot in each START command. */
    public ChargerSimulator(MqttEndpoint mqtt,ObjectMapper json,Path journalPath,Map<UUID,UUID> configuredPorts,int batteryStep,BigDecimal ignoredCapacity) {
        this(mqtt,json,journalPath,configuredPorts,batteryStep);
    }
    public synchronized void connect() {
        var scenarios=new SimulatorScenarioController(this,json);
        mqtt.start(new String[]{"campus/+/+/commands","campus/+/+/scenarios"},(topic,payload)-> {
            try {
                if(topic.endsWith("/commands")) {
                    var command=json.readValue(payload,CommandMessage.class); DeviceMessages.topicMatches(topic,command.stationId(),command.portId(),"commands"); command(command);
                } else scenarios.accept(topic,payload);
            } catch(java.io.IOException malformed) { throw new IllegalArgumentException("Malformed simulator message",malformed); }
        });
        for(var entry:ports.entrySet()) if(entry.getValue().faulted())
            emit(event(entry.getKey(),null,entry.getValue().sessionId(),"FAULT",entry.getValue().energy(),
                rebootFaults.contains(entry.getKey())?"SIMULATOR_RESTARTED_WHILE_CHARGING":"SIMULATOR_FAULT_ACTIVE"));
        rebootFaults.clear();
        for(var entry:ports.entrySet()) emit(event(entry.getKey(),null,entry.getValue().sessionId(),"HEARTBEAT",null,null));
    }
    public synchronized void syncPorts(Map<UUID,UUID> registeredPorts) {
        boolean changed=false;
        for(var entry:registeredPorts.entrySet()) {
            Port old=ports.putIfAbsent(entry.getKey(),new Port(entry.getValue(),null,false,false,0,BigDecimal.ZERO,0,0));
            if(old==null) changed=true;
            else if(!old.stationId().equals(entry.getValue())) throw new IllegalArgumentException("Simulator port station changed");
        }
        if(changed) save();
    }
    private Instant timestamp() {
        Instant now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        lastTimestamp=now.isAfter(lastTimestamp)?now:lastTimestamp.plus(1,ChronoUnit.MICROS);
        return lastTimestamp;
    }
    private Event event(UUID port,UUID command,UUID session,String type,BigDecimal energy,String description) {
        return new Event(UUID.randomUUID(),command,session,ports.get(port).stationId(),port,type,energy,timestamp(),1,description);
    }
    public synchronized Event command(CommandMessage message) {
        Port port=ports.get(message.portId());
        if(message.commandId()==null || message.sessionId()==null || port==null || !port.stationId().equals(message.stationId()) || message.schemaVersion()!=1 ||
           message.timestamp()==null || message.timestamp().isAfter(Instant.now().plusSeconds(5)) || !Set.of("START","STOP").contains(message.type()))
            throw new IllegalArgumentException("Invalid simulator command");
        if(disconnectedPorts.contains(message.portId())) return null;
        var previous=processed.get(message.commandId());
        if(previous!=null) {
            if(!previous.command().equals(message)) throw new IllegalArgumentException("Command ID reused with different payload");
            emit(previous.response()); return previous.response();
        }
        Event response;
        if(message.type().equals("START")) {
            if(message.initialBatteryPercent()==null || message.initialBatteryPercent()<0 || message.initialBatteryPercent()>99 ||
               message.batteryCapacityKwh()==null || message.batteryCapacityKwh().signum()<=0) throw new IllegalArgumentException("Invalid starting battery or vehicle capacity");
            if(port.faulted() || stopped.containsKey(message.sessionId()) || port.charging() && !message.sessionId().equals(port.sessionId()))
                response=event(message.portId(),message.commandId(),message.sessionId(),"COMMAND_REJECTED",null,"Port faulted, busy or session already stopped");
            else {
                if(!message.sessionId().equals(port.sessionId())) ports.put(message.portId(),new Port(port.stationId(),message.sessionId(),true,false,message.initialBatteryPercent(),BigDecimal.ZERO,port.starts()+1,port.stops(),message.batteryCapacityKwh()));
                response=event(message.portId(),message.commandId(),message.sessionId(),"START_ACK",null,null);
            }
        } else {
            BigDecimal finalEnergy=stopped.getOrDefault(message.sessionId(),BigDecimal.ZERO);
            if(message.sessionId().equals(port.sessionId())) {
                finalEnergy=port.energy();
                int effects=port.stops()+(stopped.containsKey(message.sessionId())?0:1);
                ports.put(message.portId(),new Port(port.stationId(),port.sessionId(),false,port.faulted(),port.battery(),port.energy(),port.starts(),effects,port.batteryCapacityKwh()));
            }
            // STOP is also a durable tombstone when START has not arrived yet. A late START cannot energize this session.
            stopped.put(message.sessionId(),finalEnergy);
            response=event(message.portId(),message.commandId(),message.sessionId(),"STOP_ACK",finalEnergy,null);
        }
        processed.put(message.commandId(),new Processed(message,response)); save(); emit(response); return response;
    }
    public synchronized void tick() {
        if(heartbeatsPaused) return;
        for(UUID id:new ArrayList<>(ports.keySet())) {
            if(disconnectedPorts.contains(id)) continue;
            Port p=ports.get(id);
            if(p.charging()) {
                int battery=Math.min(100,p.battery()+batteryStep);
                BigDecimal energy=p.energy().add(p.batteryCapacityKwh().multiply(BigDecimal.valueOf(battery-p.battery())).movePointLeft(2));
                p=new Port(p.stationId(),p.sessionId(),battery<100,p.faulted(),battery,energy,p.starts(),p.stops(),p.batteryCapacityKwh()); ports.put(id,p); save();
                if(battery>=90 && battery-batteryStep<90) emit(event(id,null,p.sessionId(),"NEAR_FULL",energy,null));
                if(battery==100) emit(event(id,null,p.sessionId(),"FULL",energy,null));
            }
            // FAULT is a durable device state. Re-advertise it until recovery, even if the first event was lost.
            if(p.faulted()) emit(event(id,null,p.sessionId(),"FAULT",p.energy(),"SIMULATOR_FAULT_ACTIVE"));
            // Keep reporting even after local safety cut-off until the backend's STOP is acknowledged.
            // This recovers a lost final 100% update without assuming transport delivery equals device completion.
            if(p.sessionId()!=null && !stopped.containsKey(p.sessionId()))
                mqtt.publish(DeviceMessages.topic(p.stationId(),id,"telemetry"),new Telemetry(UUID.randomUUID(),p.sessionId(),p.stationId(),id,p.battery(),p.energy(),timestamp(),1));
            emit(event(id,null,p.sessionId(),"HEARTBEAT",null,null));
        }
    }
    public synchronized void fault(UUID id,String description) {
        Port p=required(id); if(!p.faulted()) faultedAt.put(id,timestamp());
        ports.put(id,new Port(p.stationId(),p.sessionId(),false,true,p.battery(),p.energy(),p.starts(),p.stops(),p.batteryCapacityKwh())); save();
        emit(event(id,null,p.sessionId(),"FAULT",p.energy(),description));
    }
    public synchronized void clearFault(UUID id) {
        Port p=required(id);
        if(p.sessionId()!=null && !stopped.containsKey(p.sessionId())) throw new IllegalStateException("Acknowledge STOP before resetting simulator fault");
        ports.put(id,new Port(p.stationId(),p.sessionId(),false,false,p.battery(),p.energy(),p.starts(),p.stops(),p.batteryCapacityKwh())); save();
    }
    public synchronized void recover(UUID request,UUID id,Instant requestedAt) {
        var previous=recoveries.get(request);
        if(previous!=null) {
            if(!previous.requestedAt().equals(requestedAt) || !previous.response().portId().equals(id)) throw new IllegalArgumentException("Recovery ID reused with different payload");
            emit(previous.response()); return;
        }
        if(request==null || requestedAt==null || requestedAt.isBefore(faultedAt.getOrDefault(id,Instant.EPOCH))) throw new IllegalArgumentException("Recovery request predates the current fault");
        // Operator recovery also repairs the demo communication link.
        if(disconnectedPorts.contains(id)) disconnectPort(id,false);
        Port recovering=required(id);
        // The durable backend recovery request will retry after STOP is acknowledged.
        if(recovering.sessionId()!=null && !stopped.containsKey(recovering.sessionId())) return;
        clearFault(id);
        Event ack=new Event(request,null,null,required(id).stationId(),id,"RECOVERY_ACK",null,timestamp(),1,null);
        recoveries.put(request,new Recovered(requestedAt,ack)); save(); emit(ack);
    }
    public synchronized void disconnectPort(UUID id,boolean disconnected) {
        Port p=required(id);
        if(disconnected) {
            disconnectedPorts.add(id);
            // Local safety cut-off; backend must still wait for STOP acknowledgement.
            ports.put(id,new Port(p.stationId(),p.sessionId(),false,p.faulted(),p.battery(),p.energy(),p.starts(),p.stops(),p.batteryCapacityKwh()));
            save();
        } else {
            disconnectedPorts.remove(id);
            emit(event(id,null,p.sessionId(),"HEARTBEAT",null,null));
        }
    }
    public synchronized void pauseHeartbeats(boolean paused) { heartbeatsPaused=paused; }
    public synchronized void requireIdentity(UUID station,UUID id) { if(!required(id).stationId().equals(station)) throw new IllegalArgumentException("Simulator station/port mismatch"); }
    public synchronized boolean isCharging(UUID id) { return required(id).charging(); }
    public synchronized int startEffects(UUID id) { return required(id).starts(); }
    public synchronized int stopEffects(UUID id) { return required(id).stops(); }
    private Port required(UUID id) { Port port=ports.get(id); if(port==null) throw new IllegalArgumentException("Unknown simulator port"); return port; }
    private void emit(Event event) { mqtt.publish(DeviceMessages.topic(event.stationId(),event.portId(),"events"),event); }
    private void save() {
        try {
            Files.createDirectories(journalPath.getParent());
            Path pending=journalPath.resolveSibling(journalPath.getFileName()+".tmp");
            json.writeValue(pending.toFile(),new Journal(ports,processed,stopped,recoveries,faultedAt));
            try { Files.move(pending,journalPath,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException unavailable) { Files.move(pending,journalPath,StandardCopyOption.REPLACE_EXISTING); }
        } catch(Exception unavailable) { throw new IllegalStateException("Simulator state cannot be persisted safely",unavailable); }
    }
    @Override public void close() { mqtt.close(); }
}
