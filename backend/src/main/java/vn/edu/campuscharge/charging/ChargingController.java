package vn.edu.campuscharge.charging;

import java.security.Principal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import vn.edu.campuscharge.api.ApiAccess;
import vn.edu.campuscharge.charging.ChargingRepository.SessionView;

@RestController
@org.springframework.context.annotation.Profile("!simulator")
@RequestMapping("/api")
public class ChargingController {
    private final ChargingService service;
    public ChargingController(ChargingService service) { this.service=service; }
    public record StartRequest(UUID vehicleId,Integer initialBatteryPercent,String targetType,Integer targetBatteryPercent,java.math.BigDecimal targetBudgetVnd) {
        ChargingRepository.StartOptions options() { return new ChargingRepository.StartOptions(vehicleId,initialBatteryPercent==null?-1:initialBatteryPercent,targetType,targetBatteryPercent,targetBudgetVnd); }
    }
    @PostMapping("/bookings/{id}/quote")
    ChargingRepository.Quote quote(Principal principal,@PathVariable UUID id,@RequestBody StartRequest body) { return service.quote(ApiAccess.user(principal),id,body.options()); }
    @PostMapping("/bookings/{id}/start") @ResponseStatus(HttpStatus.ACCEPTED)
    SessionView start(Principal principal,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody StartRequest body) {
        return service.start(ApiAccess.user(principal),id,body.options(),key);
    }
    @PostMapping("/sessions/{id}/stop") @ResponseStatus(HttpStatus.ACCEPTED)
    SessionView stop(Principal principal,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key) { return service.stop(ApiAccess.user(principal),id,key); }
    @GetMapping("/sessions/{id}") SessionView get(Principal principal,@PathVariable UUID id) { return service.get(ApiAccess.user(principal),id); }
    @GetMapping("/sessions") List<SessionView> list(Principal principal) { return service.list(ApiAccess.user(principal)); }
}
