package vn.edu.campuscharge.incident;

import java.time.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.campuscharge.api.ApiAccess;

@Service
@Profile("!simulator")
public class IncidentService {
    public record IncidentView(UUID incidentId,UUID portId,UUID sessionId,String description,String reportType,String category,
                               UUID reporterUserId,String reporterEmail,String stationName,List<UUID> attachmentIds,
                               String status,Instant reportedAt,UUID acknowledgedBy,UUID resolvedBy,Instant resolvedAt,
                               String assignedStaffName,String assignmentNote,Instant assignedAt,String repairReport,
                               Instant repairReportedAt,UUID repairReportedBy,String recoveryStatus) {}
    public record Assignment(String staffName,String note) {}
    public record RepairReport(String report) {}
    public record Attachment(byte[] data,String contentType,String fileName) {}
    private record PendingImage(String fileName,String contentType,byte[] bytes) {}
    public record PortView(UUID portId,String status,Instant lastSeenAt) {}
    public record RecoveryView(UUID recoveryId,UUID stationId,UUID portId,Instant requestedAt,Instant acknowledgedAt,String status) {}
    private record LockedPort(UUID id,boolean stationActive,String status,Instant lastSeen,List<UUID> activeSessions) {}
    private final JdbcTemplate jdbc;
    private final Duration heartbeatTimeout;
    public IncidentService(JdbcTemplate jdbc,@Value("${device.heartbeat-timeout-seconds:15}") long seconds) { this.jdbc=jdbc; this.heartbeatTimeout=Duration.ofSeconds(seconds); }
    private IncidentView row(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        var array=r.getArray("attachment_ids");
        List<UUID> attachments=List.of();
        if(array!=null) {
            Object raw=array.getArray();
            if(raw instanceof Object[] values) attachments=Arrays.stream(values).map(value -> value instanceof UUID id?id:UUID.fromString(value.toString())).toList();
            array.free();
        }
        return new IncidentView(r.getObject("id",UUID.class),r.getObject("port_id",UUID.class),r.getObject("session_id",UUID.class),r.getString("description"),r.getString("report_type"),r.getString("category"),r.getObject("reporter_user_id",UUID.class),r.getString("reporter_email"),r.getString("station_name"),attachments,r.getString("status"),r.getTimestamp("reported_at").toInstant(),
            r.getObject("acknowledged_by",UUID.class),r.getObject("resolved_by",UUID.class),r.getTimestamp("resolved_at")==null?null:r.getTimestamp("resolved_at").toInstant(),
            r.getString("assigned_staff_name"),r.getString("assignment_note"),r.getTimestamp("assigned_at")==null?null:r.getTimestamp("assigned_at").toInstant(),
            r.getString("repair_report"),r.getTimestamp("repair_reported_at")==null?null:r.getTimestamp("repair_reported_at").toInstant(),r.getObject("repair_reported_by",UUID.class),r.getString("recovery_status"));
    }
    private static final String INCIDENT_SELECT="""
        SELECT i.*, st.name AS station_name, u.email AS reporter_email,
          coalesce((SELECT array_agg(a.id ORDER BY a.uploaded_at,a.id) FROM incident_attachments a WHERE a.incident_id=i.id),'{}'::uuid[]) AS attachment_ids,
          recovery.status AS recovery_status
        FROM incidents i JOIN ports p ON p.id=i.port_id JOIN stations st ON st.id=p.station_id
        LEFT JOIN users u ON u.id=i.reporter_user_id
        LEFT JOIN LATERAL (
          SELECT CASE WHEN r.acknowledged_at > greatest(
              coalesce((SELECT max(recorded_at) FROM device_events WHERE port_id=r.port_id AND type='FAULT'),'-infinity'::timestamptz),
              coalesce((SELECT max(reported_at) FROM incidents WHERE port_id=r.port_id),'-infinity'::timestamptz))
            THEN 'ACKNOWLEDGED' ELSE 'PENDING' END AS status
          FROM port_recovery_requests r
          WHERE r.port_id=i.port_id AND r.superseded_at IS NULL
          ORDER BY r.requested_at DESC,r.id DESC LIMIT 1
        ) recovery ON true
        """;
    public List<IncidentView> list() { return jdbc.query(INCIDENT_SELECT+" ORDER BY i.reported_at DESC,i.id",this::row); }
    public List<IncidentView> listMine(UUID user) { return jdbc.query(INCIDENT_SELECT+" WHERE i.reporter_user_id=? ORDER BY i.reported_at DESC,i.id",this::row,user); }
    @Transactional public IncidentView createCustomerReport(UUID user,UUID portId,UUID sessionId,String category,String description,List<MultipartFile> uploads,String idempotencyKey) {
        var allowed=Set.of("START_CHARGE_FAILED","GUN_STUCK","INCORRECT_BILLING","DISPLAY_FAILURE","STOP_CHARGE_FAILED","OTHER");
        if(!allowed.contains(category)) throw badRequest("Chọn một nhóm lỗi hợp lệ.");
        String details=description==null?"":description.trim();
        if(details.length()>1500) throw badRequest("Mô tả không được vượt quá 1.500 ký tự.");
        if(category.equals("OTHER") && details.isBlank()) throw badRequest("Hãy mô tả lỗi khác để nhân viên có thể hỗ trợ.");
        List<PendingImage> images=readImages(uploads);
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",UUID.class,user);
        var prior=jdbc.query(INCIDENT_SELECT+" WHERE i.reporter_user_id=? AND i.idempotency_key=?",this::row,user,idempotencyKey);
        if(!prior.isEmpty()) return prior.getFirst();
        if(jdbc.queryForList("SELECT id FROM ports WHERE id=?",UUID.class,portId).isEmpty()) throw ApiAccess.missing();
        if(sessionId!=null && !Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM charging_sessions cs JOIN bookings b ON b.id=cs.booking_id
                WHERE cs.id=? AND cs.port_id=? AND b.user_id=?)
            """,Boolean.class,sessionId,portId,user))) throw badRequest("Phiên sạc không thuộc tài khoản này hoặc không khớp cổng đã chọn.");
        UUID id=UUID.randomUUID();
        String summary=details.isBlank()?categoryLabel(category):details;
        jdbc.update("INSERT INTO incidents(id,port_id,session_id,description,report_type,category,reporter_user_id,idempotency_key) VALUES(?,?,?,?, 'CUSTOMER',?,?,?)",
                id,portId,sessionId,summary,category,user,idempotencyKey);
        for(var image:images) jdbc.update("INSERT INTO incident_attachments(incident_id,file_name,content_type,file_data) VALUES(?,?,?,?)",id,image.fileName(),image.contentType(),image.bytes());
        return find(id,false);
    }
    public Attachment attachment(UUID incidentId,UUID attachmentId,UUID owner) {
        var rows=jdbc.query("""
            SELECT a.file_data,a.content_type,a.file_name FROM incident_attachments a JOIN incidents i ON i.id=a.incident_id
            WHERE i.id=? AND a.id=? AND (?::uuid IS NULL OR i.reporter_user_id=?)
            """,(r,n)->new Attachment(r.getBytes("file_data"),r.getString("content_type"),r.getString("file_name")),incidentId,attachmentId,owner,owner);
        if(rows.isEmpty()) throw ApiAccess.missing(); return rows.getFirst();
    }
    private static List<PendingImage> readImages(List<MultipartFile> uploads) {
        if(uploads==null || uploads.isEmpty()) return List.of();
        if(uploads.size()>3) throw badRequest("Bạn có thể đính kèm tối đa 3 ảnh.");
        List<PendingImage> result=new ArrayList<>();
        for(var upload:uploads) {
            if(upload==null || upload.isEmpty()) continue;
            if(upload.getSize()>5L*1024*1024) throw badRequest("Mỗi ảnh không được vượt quá 5 MB.");
            try {
                byte[] bytes=upload.getBytes(); String type=upload.getContentType();
                if(!validImage(bytes,type)) throw badRequest("Chỉ nhận ảnh JPEG, PNG hoặc WebP hợp lệ.");
                String original=Objects.requireNonNullElse(upload.getOriginalFilename(),"anh-su-co").replace('\\','/');
                String fileName=original.substring(original.lastIndexOf('/')+1).replaceAll("[^\\p{L}\\p{N}._ -]","_").trim();
                if(fileName.isBlank()) fileName="anh-su-co";
                if(fileName.length()>120) fileName=fileName.substring(fileName.length()-120);
                result.add(new PendingImage(fileName,type,bytes));
            } catch(IOException e) { throw badRequest("Không thể đọc tệp ảnh vừa chọn."); }
        }
        return result;
    }
    private static boolean validImage(byte[] bytes,String type) {
        if(bytes==null || type==null) return false;
        if(type.equals("image/jpeg")) return bytes.length>3 && (bytes[0]&255)==0xff && (bytes[1]&255)==0xd8 && (bytes[2]&255)==0xff;
        if(type.equals("image/png")) return bytes.length>=8 && Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a});
        if(type.equals("image/webp")) return bytes.length>=12 && new String(bytes,0,4,StandardCharsets.US_ASCII).equals("RIFF") && new String(bytes,8,4,StandardCharsets.US_ASCII).equals("WEBP");
        return false;
    }
    private static String categoryLabel(String category) { return switch(category) {
        case "START_CHARGE_FAILED" -> "Không bắt đầu sạc được"; case "GUN_STUCK" -> "Súng sạc bị kẹt";
        case "INCORRECT_BILLING" -> "Tính tiền chưa chính xác"; case "DISPLAY_FAILURE" -> "Trụ hoặc màn hình không hoạt động";
        case "STOP_CHARGE_FAILED" -> "Không thể kết thúc phiên sạc"; default -> "Lỗi khác";
    }; }
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private IncidentView find(UUID id,boolean lock) {
        var rows=jdbc.query(INCIDENT_SELECT+" WHERE i.id=?"+(lock?" FOR UPDATE OF i":""),this::row,id);
        if(rows.isEmpty()) throw ApiAccess.missing(); return rows.getFirst();
    }
    // Match device fault handling: station -> active sessions -> bookings -> port -> incident.
    private LockedPort lockPort(UUID id) {
        var stations=jdbc.query("SELECT station_id FROM ports WHERE id=?",(r,n)->r.getObject(1,UUID.class),id);
        if(stations.isEmpty()) throw ApiAccess.missing();
        boolean active=Boolean.TRUE.equals(jdbc.queryForObject("SELECT active FROM stations WHERE id=? FOR UPDATE",Boolean.class,stations.getFirst()));
        var sessions=jdbc.query("SELECT id FROM charging_sessions WHERE port_id=? AND status<>'COMPLETED' FOR UPDATE",(r,n)->r.getObject(1,UUID.class),id);
        jdbc.query("SELECT id FROM bookings WHERE port_id=? AND status IN ('CONFIRMED','IN_USE') ORDER BY start_at,id FOR UPDATE",(r,n)->r.getObject(1,UUID.class),id);
        return jdbc.queryForObject("SELECT status,last_seen_at FROM ports WHERE id=? FOR NO KEY UPDATE",(r,n)->new LockedPort(id,active,r.getString("status"),r.getTimestamp("last_seen_at")==null?null:r.getTimestamp("last_seen_at").toInstant(),sessions),id);
    }
    @Transactional public IncidentView assign(UUID actor,UUID id,String staffName,String note) {
        lockPort(find(id,false).portId()); var incident=find(id,true);
        String staff=staffName==null?"":staffName.trim(); String details=note==null?"":note.trim();
        if(staff.isBlank() || staff.length()>120) throw badRequest("Nhập tên nhân viên được phân công (tối đa 120 ký tự).");
        if(details.length()>1000) throw badRequest("Ghi chú phân công không được vượt quá 1.000 ký tự.");
        if(!Set.of("OPEN","ASSIGNED").contains(incident.status())) throw ApiAccess.conflict("Chỉ có thể phân công sự cố mới hoặc cập nhật người được phân công");
        jdbc.update("""
            UPDATE incidents SET status='ASSIGNED',acknowledged_by=coalesce(acknowledged_by,?),
                assigned_staff_name=?,assignment_note=?,assigned_at=coalesce(assigned_at,now()) WHERE id=?
            """,actor,staff,details.isBlank()?null:details,id);
        return find(id,false);
    }
    @Transactional public IncidentView reportRepair(UUID actor,UUID id,String report) {
        lockPort(find(id,false).portId()); var incident=find(id,true);
        String details=report==null?"":report.trim();
        if(details.isBlank() || details.length()>2000) throw badRequest("Nhập nội dung nhân viên báo đã xử lý (tối đa 2.000 ký tự).");
        if(!incident.status().equals("ASSIGNED")) throw ApiAccess.conflict("Chỉ ghi nhận báo sửa sau khi đã phân công nhân viên");
        jdbc.update("UPDATE incidents SET status='REPAIR_REPORTED',repair_report=?,repair_reported_at=now(),repair_reported_by=? WHERE id=?",details,actor,id);
        return find(id,false);
    }
    @Transactional public IncidentView resolve(UUID actor,UUID id) {
        var port=lockPort(find(id,false).portId()); var incident=find(id,true);
        if(incident.status().equals("RESOLVED")) return incident;
        if(!incident.status().equals("REPAIR_REPORTED")) throw ApiAccess.conflict("Cần ghi nhận nhân viên đã báo sửa trước khi admin xác nhận");
        if(!port.stationActive()) throw ApiAccess.conflict("Khuôn viên bệnh viện đang tạm ngưng hoạt động");
        if(!port.activeSessions().isEmpty()) throw ApiAccess.conflict("Phiên sạc vẫn đang hoạt động; chờ thiết bị xác nhận dừng trước khi mở cổng");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM incidents WHERE port_id=? AND id<>? AND status<>'RESOLVED')",Boolean.class,port.id(),id)))
            throw ApiAccess.conflict("Cổng còn sự cố khác chưa được admin xác nhận hoàn tất");
        requireFreshHeartbeat(port);
        if(incident.reportType().equals("DEVICE") || Set.of("FAULTED","OFFLINE").contains(port.status())) requireDeviceRecovery(port);
        if(!Set.of("AVAILABLE","FAULTED","OFFLINE","DISABLED").contains(port.status())) throw ApiAccess.conflict("Trạng thái cổng chưa an toàn để đưa vào hoạt động");
        jdbc.update("UPDATE incidents SET status='RESOLVED',resolved_by=?,resolved_at=now() WHERE id=?",actor,id);
        jdbc.update("UPDATE ports SET status='AVAILABLE' WHERE id=?",port.id());
        return find(id,false);
    }
    private void safeToRecover(LockedPort port) {
        if(!port.activeSessions().isEmpty()) throw ApiAccess.conflict("Wait for the device STOP acknowledgement before recovery");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM incidents WHERE port_id=? AND status IN ('OPEN','ASSIGNED') AND status<>'RESOLVED')",Boolean.class,port.id())))
            throw ApiAccess.conflict("Nhân viên cần báo đã xử lý trước khi yêu cầu thiết bị xác nhận phục hồi");
    }
    private void safeToRestore(LockedPort port) {
        safeToRecover(port);
        if(!port.stationActive() || Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM incidents WHERE port_id=? AND status<>'RESOLVED')",Boolean.class,port.id())))
            throw ApiAccess.conflict("Resolve all incidents and activate the station before restoring service");
        requireFreshHeartbeat(port);
        if(!port.status().equals("DISABLED")) requireDeviceRecovery(port);
    }
    private void requireFreshHeartbeat(LockedPort port) {
        if(port.lastSeen()==null || port.lastSeen().isBefore(Instant.now().minus(heartbeatTimeout))) throw ApiAccess.conflict("A fresh device heartbeat is required");
    }
    private void requireDeviceRecovery(LockedPort port) {
        Boolean healthy=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM port_recovery_requests r JOIN device_events e ON e.event_id=r.id
                WHERE r.port_id=? AND r.superseded_at IS NULL AND r.acknowledged_at IS NOT NULL
                AND e.port_id=r.port_id AND e.type='RECOVERY_ACK'
                AND r.acknowledged_at>greatest(
                    coalesce((SELECT max(recorded_at) FROM device_events WHERE port_id=r.port_id AND type='FAULT'),'-infinity'::timestamptz),
                    coalesce((SELECT max(reported_at) FROM incidents WHERE port_id=r.port_id),'-infinity'::timestamptz)))
            """,Boolean.class,port.id());
        if(!Boolean.TRUE.equals(healthy)) throw ApiAccess.conflict("A fresh device-confirmed recovery newer than the fault and incident is required");
    }
    public RecoveryView recovery(UUID id) {
        var rows=jdbc.query("""
            SELECT r.*,p.station_id FROM port_recovery_requests r JOIN ports p ON p.id=r.port_id WHERE r.id=?
            """,(r,n)->new RecoveryView(r.getObject("id",UUID.class),r.getObject("station_id",UUID.class),r.getObject("port_id",UUID.class),
                r.getTimestamp("requested_at").toInstant(),r.getTimestamp("acknowledged_at")==null?null:r.getTimestamp("acknowledged_at").toInstant(),
                r.getTimestamp("acknowledged_at")==null?"PENDING":"ACKNOWLEDGED"),id);
        if(rows.isEmpty()) throw ApiAccess.missing(); return rows.getFirst();
    }
    @Transactional public RecoveryView requestRecovery(UUID id) {
        var port=lockPort(id);
        if(port.status().equals("OFFLINE")) {
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM incidents WHERE port_id=? AND status IN ('OPEN','ASSIGNED') AND status<>'RESOLVED')",Boolean.class,id)))
                throw ApiAccess.conflict("Nhân viên cần báo đã xử lý trước khi yêu cầu thiết bị xác nhận phục hồi");
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM charging_sessions WHERE port_id=? AND status NOT IN ('STOP_REQUESTED','COMPLETED'))",Boolean.class,id)))
                throw ApiAccess.conflict("Request STOP before reconnecting the device");
        } else safeToRecover(port);
        jdbc.update("""
            UPDATE port_recovery_requests SET superseded_at=now() WHERE port_id=? AND acknowledged_at IS NULL AND superseded_at IS NULL
                AND requested_at<=greatest(
                    coalesce((SELECT max(recorded_at) FROM device_events WHERE port_id=? AND type='FAULT'),'-infinity'::timestamptz),
                    coalesce((SELECT max(reported_at) FROM incidents WHERE port_id=?),'-infinity'::timestamptz))
            """,id,id,id);
        var reusable=jdbc.query("""
            SELECT r.id FROM port_recovery_requests r WHERE r.port_id=? AND r.superseded_at IS NULL
                AND (r.acknowledged_at IS NULL OR r.acknowledged_at>greatest(
                    coalesce((SELECT max(recorded_at) FROM device_events WHERE port_id=r.port_id AND type='FAULT'),'-infinity'::timestamptz),
                    coalesce((SELECT max(reported_at) FROM incidents WHERE port_id=r.port_id),'-infinity'::timestamptz)))
                ORDER BY requested_at DESC,id LIMIT 1
            """,(r,n)->r.getObject(1,UUID.class),id);
        if(!reusable.isEmpty()) return recovery(reusable.getFirst());
        UUID request=UUID.randomUUID(); jdbc.update("INSERT INTO port_recovery_requests(id,port_id,requested_at) VALUES (?,?,?)",request,id,Instant.now().atOffset(ZoneOffset.UTC));
        return recovery(request);
    }
    @Transactional public PortView restore(UUID id) {
        var port=lockPort(id);
        if(!Set.of("AVAILABLE","FAULTED","OFFLINE").contains(port.status())) throw ApiAccess.conflict("Port is disabled or busy");
        safeToRestore(port); jdbc.update("UPDATE ports SET status='AVAILABLE' WHERE id=?",id);
        return new PortView(id,"AVAILABLE",port.lastSeen());
    }
    @Transactional public PortView setEnabled(UUID id,boolean enabled) {
        var port=lockPort(id);
        if(enabled) safeToRestore(port);
        else if(!port.activeSessions().isEmpty()) throw ApiAccess.conflict("Stop the active session before disabling its port");
        String state=enabled?"AVAILABLE":"DISABLED";
        jdbc.update("UPDATE ports SET status=? WHERE id=?",state,id);
        return new PortView(id,state,port.lastSeen());
    }
}
