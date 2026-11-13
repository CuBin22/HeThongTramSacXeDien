package vn.edu.campuscharge.incident;

import java.security.Principal;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.edu.campuscharge.api.ApiAccess;
import vn.edu.campuscharge.device.DeviceRecoveryService;

@RestController
@Profile("!simulator")
@RequestMapping("/api")
public class IncidentController {
    private final IncidentService service;
    private final DeviceRecoveryService recovery;
    public IncidentController(IncidentService service,DeviceRecoveryService recovery) { this.service=service; this.recovery=recovery; }
    @GetMapping("/incidents") List<IncidentService.IncidentView> list() { return service.list(); }
    @PostMapping(value="/incidents/report",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
    IncidentService.IncidentView report(Principal principal,@RequestHeader("Idempotency-Key") String key,
            @RequestParam UUID portId,@RequestParam(required=false) UUID sessionId,@RequestParam String category,
            @RequestParam(required=false) String description,@RequestParam(required=false) List<org.springframework.web.multipart.MultipartFile> images) {
        return service.createCustomerReport(ApiAccess.user(principal),portId,sessionId,category,description,images,ApiAccess.key(key));
    }
    @GetMapping("/my/incidents") List<IncidentService.IncidentView> mine(Principal principal) { return service.listMine(ApiAccess.user(principal)); }
    @GetMapping("/my/incidents/{id}/attachments/{attachmentId}") ResponseEntity<byte[]> ownAttachment(Principal principal,@PathVariable UUID id,@PathVariable UUID attachmentId) {
        return image(service.attachment(id,attachmentId,ApiAccess.user(principal)));
    }
    @GetMapping("/incidents/{id}/attachments/{attachmentId}") ResponseEntity<byte[]> staffAttachment(@PathVariable UUID id,@PathVariable UUID attachmentId) {
        return image(service.attachment(id,attachmentId,null));
    }
    @PostMapping("/incidents/{id}/assign") IncidentService.IncidentView assign(Principal principal,@PathVariable UUID id,@RequestBody IncidentService.Assignment request) {
        return service.assign(ApiAccess.user(principal),id,request.staffName(),request.note());
    }
    @PostMapping("/incidents/{id}/repair-reported") IncidentService.IncidentView reportRepair(Principal principal,@PathVariable UUID id,@RequestBody IncidentService.RepairReport request) {
        return service.reportRepair(ApiAccess.user(principal),id,request.report());
    }
    @PostMapping("/incidents/{id}/resolve") IncidentService.IncidentView resolve(Principal principal,@PathVariable UUID id) { return service.resolve(ApiAccess.user(principal),id); }
    @PostMapping("/admin/ports/{id}/recover") @ResponseStatus(HttpStatus.ACCEPTED)
    IncidentService.RecoveryView recover(@PathVariable UUID id) { return recovery.recover(id); }
    private static ResponseEntity<byte[]> image(IncidentService.Attachment attachment) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(attachment.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.inline().filename(attachment.fileName(),java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options","nosniff").header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(attachment.data());
    }
}
