package vn.edu.campuscharge.charging;

import java.math.BigDecimal;
import java.util.Objects;

/** Delivered energy in kilowatt-hours; never a floating-point quantity. */
public record EnergyKwh(BigDecimal value) {
    public EnergyKwh {
        Objects.requireNonNull(value, "energy");
        if (value.signum() < 0) throw new IllegalArgumentException("Energy cannot be negative");
        value = value.stripTrailingZeros();
    }
}
