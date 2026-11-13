package vn.edu.campuscharge.payment;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PaymentTransactionTest {
 private PaymentTransaction payment() { return new PaymentTransaction(UUID.randomUUID(),UUID.randomUUID(),"sandbox-reference",new Money(new BigDecimal("3750"))); }
 @Test void verifiedResultSucceedsAndDuplicateResultIsSafe() {
  var p=payment(); p.succeed("vnp-1"); p.succeed("vnp-1");
  assertEquals(PaymentStatus.SUCCEEDED,p.status());
  assertEquals("vnp-1",p.providerTransactionId());
  assertThrows(IllegalStateException.class,p::fail);
  assertThrows(IllegalStateException.class,()->p.succeed("different"));
 }
 @Test void failedAndCancelledPaymentsCannotSucceed() {
  var p=payment(); p.fail(); assertEquals(PaymentStatus.FAILED,p.status());
  assertThrows(IllegalStateException.class,()->p.succeed("vnp-1"));
  var cancelled=payment(); cancelled.cancel(); assertEquals(PaymentStatus.CANCELLED,cancelled.status());
  assertThrows(IllegalStateException.class,cancelled::fail);
 }
 @Test void moneyAndTariffUseExactDecimalUnits() {
  var tariff=new Tariff(new Money(new BigDecimal("3000")));
  assertEquals(new Money(new BigDecimal("3750")),tariff.charge(new vn.edu.campuscharge.charging.EnergyKwh(new BigDecimal("1.25"))));
  assertEquals(new Money(new BigDecimal("1.0")),new Money(new BigDecimal("1.00")));
  assertThrows(IllegalArgumentException.class,()->new Money(new BigDecimal("-1")));
  assertThrows(IllegalArgumentException.class,()->payment().succeed(" "));
 }
}
