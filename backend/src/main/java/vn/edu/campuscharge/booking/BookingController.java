package vn.edu.campuscharge.booking;

import java.security.Principal;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import vn.edu.campuscharge.api.ApiAccess;
import vn.edu.campuscharge.booking.BookingRepository.*;

@RestController
@org.springframework.context.annotation.Profile("!simulator")
@RequestMapping("/api")
public class BookingController {
    private final BookingService service;
    private final BookingRepository repository;
    public BookingController(BookingService service,BookingRepository repository) { this.service=service; this.repository=repository; }
    public record ReserveRequest(UUID stationId,UUID portId,UUID vehicleId,Instant startAt,Instant endAt) {}
    @PostMapping("/bookings") @ResponseStatus(HttpStatus.CREATED)
    BookingView reserve(Principal principal,@RequestHeader("Idempotency-Key") String key,@RequestBody ReserveRequest body) {
        return service.reserve(ApiAccess.user(principal),body.stationId(),body.portId(),body.vehicleId(),body.startAt(),body.endAt(),key);
    }
    @PostMapping("/bookings/{id}/cancel")
    BookingView cancel(Principal principal,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key) {
        return service.cancel(ApiAccess.user(principal),id,key);
    }
    @GetMapping("/bookings") List<BookingView> list(Principal principal) { return service.list(ApiAccess.user(principal)); }
    @GetMapping("/bookings/{id}") BookingView get(Principal principal,@PathVariable UUID id) { return service.get(ApiAccess.user(principal),id); }
    @GetMapping("/stations") List<StationView> stations() { return repository.stations(); }
    @GetMapping("/stations/{id}/ports") List<PortView> ports(@PathVariable UUID id) { return repository.ports(id); }
    @GetMapping("/stations/{id}/availability") List<PortView> availablePorts(Principal principal,@PathVariable UUID id,@RequestParam UUID vehicleId,@RequestParam Instant startAt,@RequestParam Instant endAt) {
        return service.availablePorts(ApiAccess.user(principal),id,vehicleId,startAt,endAt);
    }
    @GetMapping("/stations/{id}/tariff") List<CurrentTariffView> currentTariff(@PathVariable UUID id) { return repository.currentTariff(id); }
}
