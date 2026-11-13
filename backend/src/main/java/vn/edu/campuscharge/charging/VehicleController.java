package vn.edu.campuscharge.charging;

import java.math.BigDecimal;
import java.security.Principal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import vn.edu.campuscharge.api.ApiAccess;

@RestController
@org.springframework.context.annotation.Profile("!simulator")
@RequestMapping("/api")
public class VehicleController {
    private final JdbcTemplate jdbc;
    public VehicleController(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Vehicle(UUID vehicleId,String name,String model,String connectorType,BigDecimal batteryCapacityKwh,
                          String licensePlate,Integer productionYear,BigDecimal maxChargingPowerKw,boolean isDefault) {}
    public record VehicleRequest(String name,String model,String connectorType,BigDecimal batteryCapacityKwh,
                                 String licensePlate,Integer productionYear,BigDecimal maxChargingPowerKw) {}
    public record VehicleHistory(UUID sessionId,UUID vehicleId,String vehicleName,UUID stationId,String stationName,
                                 UUID portId,java.time.OffsetDateTime startedAt,java.time.OffsetDateTime endedAt,
                                 BigDecimal energyKwh,BigDecimal totalCostVnd,BigDecimal externalPaidVnd,BigDecimal amountPaidVnd) {}
    public record HistoryResult(List<VehicleHistory> sessions,int totalSessions,BigDecimal totalEnergyKwh,
                                BigDecimal totalCostVnd,BigDecimal totalPaidVnd) {}
    @GetMapping("/vehicles") List<Vehicle> vehicles(Principal principal) {
        return jdbc.query("SELECT * FROM vehicles WHERE user_id=? ORDER BY is_default DESC,created_at,id",(r,n)->vehicle(r),ApiAccess.user(principal));
    }
    @PostMapping("/vehicles") @ResponseStatus(HttpStatus.CREATED) @Transactional
    Vehicle createVehicle(Principal principal,@RequestBody VehicleRequest body) {
        if(body==null || blank(body.name()) || blank(body.model()) || blank(body.connectorType()) || body.batteryCapacityKwh()==null || body.batteryCapacityKwh().signum()<=0
                || body.productionYear()!=null && (body.productionYear()<1900 || body.productionYear()>java.time.Year.now().getValue()+1)
                || body.maxChargingPowerKw()!=null && body.maxChargingPowerKw().signum()<=0)
            throw ApiAccess.conflict("Nhập tên xe, mẫu xe, loại cổng và dung lượng pin hợp lệ");
        UUID user=ApiAccess.user(principal);
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",UUID.class,user);
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO vehicles(id,user_id,name,model,connector_type,battery_capacity_kwh,license_plate,production_year,max_charging_power_kw,is_default) " +
                "VALUES(?,?,?,?,?,?,?,?,?,NOT EXISTS(SELECT 1 FROM vehicles WHERE user_id=?))",id,user,body.name().trim(),body.model().trim(),body.connectorType().trim(),body.batteryCapacityKwh(),trimToNull(body.licensePlate()),body.productionYear(),body.maxChargingPowerKw(),user);
        return jdbc.queryForObject("SELECT * FROM vehicles WHERE id=?",(r,n)->vehicle(r),id);
    }
    @PutMapping("/vehicles/{id}") Vehicle updateVehicle(Principal principal,@PathVariable UUID id,@RequestBody VehicleRequest body) {
        if(body==null || blank(body.name()) || blank(body.model()) || blank(body.connectorType()) || body.batteryCapacityKwh()==null || body.batteryCapacityKwh().signum()<=0
                || body.productionYear()!=null && (body.productionYear()<1900 || body.productionYear()>java.time.Year.now().getValue()+1)
                || body.maxChargingPowerKw()!=null && body.maxChargingPowerKw().signum()<=0)
            throw ApiAccess.conflict("Thông tin phương tiện chưa hợp lệ");
        int changed=jdbc.update("UPDATE vehicles SET name=?,model=?,connector_type=?,battery_capacity_kwh=?,license_plate=?,production_year=?,max_charging_power_kw=? WHERE id=? AND user_id=?",
                body.name().trim(),body.model().trim(),body.connectorType().trim(),body.batteryCapacityKwh(),trimToNull(body.licensePlate()),body.productionYear(),body.maxChargingPowerKw(),id,ApiAccess.user(principal));
        if(changed==0) throw ApiAccess.missing();
        return jdbc.queryForObject("SELECT * FROM vehicles WHERE id=?",(r,n)->vehicle(r),id);
    }
    @PostMapping("/vehicles/{id}/default") @Transactional Vehicle makeDefault(Principal principal,@PathVariable UUID id) {
        UUID user=ApiAccess.user(principal);
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",UUID.class,user);
        if(jdbc.queryForList("SELECT id FROM vehicles WHERE id=? AND user_id=?",UUID.class,id,user).isEmpty()) throw ApiAccess.missing();
        jdbc.update("UPDATE vehicles SET is_default=false WHERE user_id=? AND is_default",user);
        jdbc.update("UPDATE vehicles SET is_default=true WHERE id=? AND user_id=?",id,user);
        return jdbc.queryForObject("SELECT * FROM vehicles WHERE id=?",(r,n)->vehicle(r),id);
    }
    @GetMapping("/vehicles/history") HistoryResult history(Principal principal,
            @RequestParam(required=false) String month,@RequestParam(required=false) UUID vehicleId,@RequestParam(required=false) UUID stationId) {
        if(month!=null && !month.isBlank() && !month.matches("\\d{4}-(0[1-9]|1[0-2])")) throw ApiAccess.conflict("Tháng cần theo định dạng YYYY-MM");
        StringBuilder sql=new StringBuilder("SELECT * FROM v_user_vehicle_charging_history WHERE user_id=?");
        java.util.ArrayList<Object> args=new java.util.ArrayList<>(); args.add(ApiAccess.user(principal));
        if(month!=null && !month.isBlank()){sql.append(" AND started_at >= (?::date::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh') AND started_at < ((?::date + interval '1 month')::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')");args.add(month+"-01");args.add(month+"-01");}
        if(vehicleId!=null){sql.append(" AND vehicle_id=?");args.add(vehicleId);}
        if(stationId!=null){sql.append(" AND station_id=?");args.add(stationId);}
        sql.append(" ORDER BY started_at DESC,session_id");
        List<VehicleHistory> rows=jdbc.query(sql.toString(),(r,n)->new VehicleHistory(r.getObject("session_id",UUID.class),r.getObject("vehicle_id",UUID.class),r.getString("vehicle_name"),r.getObject("station_id",UUID.class),r.getString("station_name"),r.getObject("port_id",UUID.class),r.getObject("started_at",java.time.OffsetDateTime.class),r.getObject("ended_at",java.time.OffsetDateTime.class),r.getBigDecimal("energy_kwh"),r.getBigDecimal("total_cost_vnd"),r.getBigDecimal("external_paid_vnd"),r.getBigDecimal("amount_paid_vnd")),args.toArray());
        return new HistoryResult(rows,rows.size(),rows.stream().map(VehicleHistory::energyKwh).reduce(BigDecimal.ZERO,BigDecimal::add),rows.stream().map(VehicleHistory::totalCostVnd).reduce(BigDecimal.ZERO,BigDecimal::add),rows.stream().map(VehicleHistory::amountPaidVnd).reduce(BigDecimal.ZERO,BigDecimal::add));
    }
    private static Vehicle vehicle(ResultSet r) throws SQLException { return new Vehicle(r.getObject("id",UUID.class),r.getString("name"),r.getString("model"),r.getString("connector_type"),r.getBigDecimal("battery_capacity_kwh"),r.getString("license_plate"),(Integer)r.getObject("production_year"),r.getBigDecimal("max_charging_power_kw"),r.getBoolean("is_default")); }
    private static boolean blank(String value) { return value==null || value.isBlank(); }
    private static String trimToNull(String value) { return blank(value)?null:value.trim(); }
}
