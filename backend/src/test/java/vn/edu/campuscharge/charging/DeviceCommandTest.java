package vn.edu.campuscharge.charging;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DeviceCommandTest {
 @Test void timeoutDoesNotPretendDeviceStoppedAndLateAcknowledgementIsAccepted() {
  var c=new DeviceCommand(UUID.randomUUID(),UUID.randomUUID(),DeviceCommand.Type.STOP,"stop-key");
  c.markSent(); c.timeOut(); assertEquals(DeviceCommandStatus.TIMED_OUT,c.status());
  c.acknowledge(); c.acknowledge(); assertEquals(DeviceCommandStatus.ACKNOWLEDGED,c.status());
  assertThrows(IllegalStateException.class,c::fail);
 }
 @Test void rejectsUnsentAcknowledgementAndResendingTerminalCommand() {
  var c=new DeviceCommand(UUID.randomUUID(),UUID.randomUUID(),DeviceCommand.Type.START,"start-key");
  assertThrows(IllegalStateException.class,c::acknowledge);
  c.markSent(); c.fail(); assertEquals(DeviceCommandStatus.FAILED,c.status());
  assertThrows(IllegalStateException.class,c::markSent);
 }
}
