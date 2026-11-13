package vn.edu.campuscharge.admin;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import vn.edu.campuscharge.identity.*;
import vn.edu.campuscharge.booking.BookingRepository;
import vn.edu.campuscharge.incident.IncidentService;

@RestController
@Profile("!simulator")
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminService service;
    public AdminController(AdminService service) { this.service=service; }
    public record UserRequest(UserRole role,Boolean enabled) {}
    public record CreateStationRequest(String name,String connectorType) {}
    public record StationRequest(String name,Boolean active) {}
    public record PortRequest(Boolean enabled) {}
    public record PortPowerRequest(BigDecimal maxPowerKw) {}
    public record CreatePortRequest(String chargingMode,BigDecimal maxPowerKw) {}
    public record CreateChargerRequest(String chargingMode,String connectorType,BigDecimal maxPowerKw) {}
    public record TariffRequest(BigDecimal dayPricePerKwh,BigDecimal eveningPricePerKwh,LocalTime dayStart,LocalTime dayEnd,
        LocalTime eveningStart,LocalTime eveningEnd,Integer occupancyGraceMinutes,BigDecimal occupancyFeePerMinute,Instant effectiveAt,BigDecimal pricePerKwh) {}
    @GetMapping("/users") List<AuthService.UserView> users() { return service.users(); }
    @PutMapping("/users/{id}") AuthService.UserView user(@PathVariable UUID id,@RequestBody UserRequest request) { return service.updateUser(id,request.role(),request.enabled()); }
    @GetMapping("/chargers") List<BookingRepository.PortView> chargers() { return service.chargers(); }
    @PostMapping("/chargers") @ResponseStatus(HttpStatus.CREATED) BookingRepository.PortView createCharger(@RequestBody CreateChargerRequest request) { return service.createCharger(request.chargingMode(),request.connectorType(),request.maxPowerKw()); }
    @PostMapping("/stations") @ResponseStatus(HttpStatus.CREATED) BookingRepository.StationView createStation(@RequestBody CreateStationRequest request) { return service.createStation(request.name(),request.connectorType()); }
    @PutMapping("/stations/{id}") BookingRepository.StationView station(@PathVariable UUID id,@RequestBody StationRequest request) { return service.updateStation(id,request.name(),request.active()); }
    @PostMapping("/stations/{id}/ports") @ResponseStatus(HttpStatus.CREATED) IncidentService.PortView createPort(@PathVariable UUID id,@RequestBody CreatePortRequest request) { return service.createPort(id,request.chargingMode(),request.maxPowerKw()); }
    @PutMapping("/ports/{id}") IncidentService.PortView port(@PathVariable UUID id,@RequestBody PortRequest request) { return service.updatePort(id,request.enabled()); }
    @PutMapping("/ports/{id}/power") AdminService.PortPowerView portPower(@PathVariable UUID id,@RequestBody PortPowerRequest request) { return service.updatePortPower(id,request.maxPowerKw()); }
    @GetMapping("/tariffs") List<AdminService.TariffView> tariffs() { return service.tariffs(); }
    @PostMapping("/tariffs") @ResponseStatus(HttpStatus.CREATED) AdminService.TariffView tariff(@RequestBody TariffRequest request) { return service.createTariff(request.dayPricePerKwh(),request.eveningPricePerKwh(),request.dayStart(),request.dayEnd(),request.eveningStart(),request.eveningEnd(),request.occupancyGraceMinutes()==null?15:request.occupancyGraceMinutes(),request.occupancyFeePerMinute()==null?new BigDecimal("1000"):request.occupancyFeePerMinute(),request.effectiveAt(),request.pricePerKwh()); }
    @GetMapping("/reports") AdminService.Report report() { return service.report(); }
}
