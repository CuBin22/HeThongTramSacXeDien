package vn.edu.campuscharge.booking;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BookingTest {
 private Booking booking() { return new Booking(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-10-25T10:00:00Z"), Instant.parse("2026-10-25T11:00:00Z")); }
 @Test void consumesReservationThenCompletes() {
  var booking=booking();
  assertEquals(BookingStatus.CONFIRMED, booking.status());
  booking.start(); assertEquals(BookingStatus.IN_USE, booking.status());
  assertThrows(IllegalStateException.class, booking::cancel);
  booking.complete(); assertEquals(BookingStatus.COMPLETED, booking.status());
  assertThrows(IllegalStateException.class, booking::start);
 }
 @Test void confirmedBookingCanBeCancelledExpiredOrUnserviceable() {
  var cancelled=booking(); cancelled.cancel(); assertEquals(BookingStatus.CANCELLED,cancelled.status());
  assertThrows(IllegalStateException.class,cancelled::start);
  var expired=booking(); expired.expire(); assertEquals(BookingStatus.EXPIRED,expired.status());
  var unavailable=booking(); unavailable.markUnserviceable(); assertEquals(BookingStatus.UNSERVICEABLE,unavailable.status());
 }
 @Test void cannotCompleteBeforeStartOrReserveEmptyInterval() {
  assertThrows(IllegalStateException.class,booking()::complete);
  var now=Instant.now();
  assertThrows(IllegalArgumentException.class,()->new Booking(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),now,now));
 }
}
