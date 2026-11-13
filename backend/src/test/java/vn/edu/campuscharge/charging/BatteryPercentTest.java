package vn.edu.campuscharge.charging;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BatteryPercentTest {
 @Test void acceptsEveryStartingPercentBelowFull() {
  for (int value=0; value<100; value++) assertEquals(value, BatteryPercent.starting(value).value());
 }
 @Test void rejectsInvalidStartingPercent() {
  for (int value : new int[]{-1,100,101}) assertThrows(IllegalArgumentException.class, () -> BatteryPercent.starting(value));
 }
 @Test void telemetryAllowsFullAndMarksNearFull() {
  assertFalse(new BatteryPercent(89).nearFull());
  assertTrue(new BatteryPercent(90).nearFull());
  assertTrue(new BatteryPercent(100).full());
  assertThrows(IllegalArgumentException.class, () -> new BatteryPercent(101));
 }
}
