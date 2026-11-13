package vn.edu.campuscharge.payment;

import java.util.Objects;
import vn.edu.campuscharge.charging.EnergyKwh;

/** Immutable VND per kWh snapshot. Provider-specific rounding belongs to payment integration. */
public record Tariff(Money pricePerKwh) {
    public Tariff { Objects.requireNonNull(pricePerKwh, "pricePerKwh"); }

    public Money charge(EnergyKwh energy) {
        return new Money(pricePerKwh.vnd().multiply(energy.value()));
    }
}
