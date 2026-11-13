package vn.edu.campuscharge.charging;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.edu.campuscharge.payment.*;
import static org.junit.jupiter.api.Assertions.*;
class ChargingSessionTest {
 private ChargingSession session(int percent) { return new ChargingSession(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),BatteryPercent.starting(percent),new Tariff(new Money(new BigDecimal("3000")))); }
 @Test void closesOnlyAfterStopAcknowledgementAndFreezesFee() {
  var s=session(89);
  assertEquals(SessionStatus.START_REQUESTED,s.status());
  assertThrows(IllegalStateException.class,()->s.acknowledgeStopped(new EnergyKwh(new BigDecimal("1.25"))));
  s.acknowledgeStarted(); s.recordBattery(new BatteryPercent(90));
  assertTrue(s.battery().nearFull());
  s.recordBattery(new BatteryPercent(100)); assertEquals(SessionStatus.STOP_REQUESTED,s.status());
  assertNull(s.totalFee());
  s.acknowledgeStopped(new EnergyKwh(new BigDecimal("1.25")));
  assertEquals(SessionStatus.COMPLETED,s.status());
  assertEquals(new Money(new BigDecimal("3750")),s.totalFee());
  s.acknowledgeStopped(new EnergyKwh(new BigDecimal("999")));
  assertEquals(new Money(new BigDecimal("3750")),s.totalFee());
  assertThrows(IllegalStateException.class,()->s.recordBattery(new BatteryPercent(100)));
 }
 @Test void stopCanBeRequestedWhileStartAcknowledgementIsPending() {
  var s=session(0); s.requestStop(); assertEquals(SessionStatus.STOP_REQUESTED,s.status());
  assertThrows(IllegalStateException.class,s::acknowledgeStarted);
  s.acknowledgeStopped(new EnergyKwh(BigDecimal.ZERO)); assertEquals(SessionStatus.COMPLETED,s.status());
 }
 @Test void rejectsDecreasingBatteryAndInvalidLifecycleCalls() {
  var s=session(60);
  assertThrows(IllegalStateException.class,()->s.recordBattery(new BatteryPercent(61)));
  s.acknowledgeStarted();
  assertThrows(IllegalArgumentException.class,()->s.recordBattery(new BatteryPercent(59)));
  assertEquals(60,s.battery().value());
  assertThrows(IllegalStateException.class,s::acknowledgeStarted);
  s.requestStop(); s.requestStop();
  assertThrows(IllegalArgumentException.class,()->new EnergyKwh(new BigDecimal("-1")));
 }
}
