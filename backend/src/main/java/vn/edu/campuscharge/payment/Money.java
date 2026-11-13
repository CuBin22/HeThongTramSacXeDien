package vn.edu.campuscharge.payment;

import java.math.BigDecimal;
import java.util.Objects;

/** Non-negative Vietnamese dong (VND). No card or bank data is represented. */
public record Money(BigDecimal vnd) {
    public Money {
        Objects.requireNonNull(vnd, "vnd");
        if (vnd.signum() < 0) throw new IllegalArgumentException("Money cannot be negative");
        vnd = vnd.stripTrailingZeros();
    }
}
