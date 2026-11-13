package vn.edu.campuscharge.charging;

import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.campuscharge.api.ApiAccess;
import vn.edu.campuscharge.device.DeviceGateway;
import vn.edu.campuscharge.charging.ChargingRepository.*;

@Service
@org.springframework.context.annotation.Profile("!simulator")
public class ChargingService {
    private final ChargingRepository repository;
    private final ObjectProvider<DeviceGateway> gateways;
    public ChargingService(ChargingRepository repository,ObjectProvider<DeviceGateway> gateways) { this.repository=repository; this.gateways=gateways; }
    public SessionView start(UUID user,UUID booking,ChargingRepository.StartOptions options,String key) {
        ApiAccess.key(key);
        if(options==null || options.vehicleId()==null || options.targetType()==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Vehicle, battery and charging target are required");
        Command command=repository.start(user,booking,options,key);
        deliver(command);
        return repository.get(user,command.sessionId());
    }
    /** Compatibility entry point for internal callers predating saved vehicle profiles. */
    public SessionView start(UUID user,UUID booking,Integer battery,String key) {
        ApiAccess.key(key);
        if(battery==null || battery<0 || battery>99) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Starting battery must be 0 to 99");
        Command command=repository.start(user,booking,battery,key);
        deliver(command);
        return repository.get(user,command.sessionId());
    }
    public SessionView stop(UUID user,UUID id,String key) {
        ApiAccess.key(key);
        Command command=repository.stop(user,id,key,"USER_REQUEST");
        deliver(command);
        return repository.get(user,id);
    }
    private void deliver(Command command) {
        var session=repository.getInternal(command.sessionId());
        if(command.status()!=DeviceCommandStatus.PENDING && command.status()!=DeviceCommandStatus.TIMED_OUT) return;
        if(command.type()==DeviceCommand.Type.START && session.status()!=SessionStatus.START_REQUESTED ||
           command.type()==DeviceCommand.Type.STOP && session.status()!=SessionStatus.STOP_REQUESTED) return;
        DeviceGateway gateway=gateways.getIfAvailable();
        if(gateway==null) return;
        try {
            DeviceGateway.AcceptedCommand accepted=command.type()==DeviceCommand.Type.START
                ? gateway.start(command.commandId(),command.portId(),command.initialBatteryPercent())
                : gateway.stop(command.commandId(),command.portId(),command.reason());
            if(accepted!=null && command.commandId().equals(accepted.commandId())) repository.markSent(command.commandId());
        } catch(RuntimeException unavailable) {
            // The durable pending command is retried with its original ID by the device outbox or API replay.
        }
    }
    public SessionView get(UUID user,UUID id) { return repository.get(user,id); }
    public ChargingRepository.Quote quote(UUID user,UUID booking,ChargingRepository.StartOptions options) { return repository.quote(user,booking,options); }
    public java.util.List<SessionView> list(UUID user) { return repository.list(user); }
    public void acknowledgeStart(UUID commandId,UUID portId,java.time.Instant at) { repository.acknowledgeStart(commandId,portId,at); }
    public void acknowledgeStop(UUID commandId,UUID portId,java.math.BigDecimal energy,java.time.Instant at) { repository.acknowledgeStop(commandId,portId,energy,at); }
}
