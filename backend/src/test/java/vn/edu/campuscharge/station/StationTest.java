package vn.edu.campuscharge.station;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StationTest {
 @Test void onlyAvailableCompatiblePortsAreEligible() {
  var s=new Station(UUID.randomUUID(),"Campus A",true);
  var p=new ChargingPort(UUID.randomUUID(),s.id(),"E_MOTORBIKE",PortStatus.AVAILABLE);
  assertTrue(p.canReserve("E_MOTORBIKE"));
  assertFalse(p.canReserve("CAR"));
  for(var status:PortStatus.values()) {
   var port=new ChargingPort(UUID.randomUUID(),s.id(),"E_MOTORBIKE",status);
   assertEquals(status==PortStatus.AVAILABLE,port.canReserve("E_MOTORBIKE"));
  }
  assertThrows(IllegalArgumentException.class,()->new Station(UUID.randomUUID()," ",true));
 }
}
