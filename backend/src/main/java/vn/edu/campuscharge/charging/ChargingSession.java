package vn.edu.campuscharge.charging;

import java.util.Objects;
import java.util.UUID;
import vn.edu.campuscharge.payment.Money;
import vn.edu.campuscharge.payment.Tariff;

/** Pure simulator lifecycle. Command delivery and persistence are application concerns. */
public final class ChargingSession {
    private final UUID id;
    private final UUID bookingId;
    private final UUID portId;
    private final Tariff tariff;
    private final BatteryPercent initialBattery;
    private BatteryPercent battery;
    private SessionStatus status = SessionStatus.START_REQUESTED;
    private EnergyKwh energy;
    private Money totalFee;

    public ChargingSession(UUID id, UUID bookingId, UUID portId, BatteryPercent initialBattery, Tariff tariff) {
        this.id = Objects.requireNonNull(id, "id");
        this.bookingId = Objects.requireNonNull(bookingId, "bookingId");
        this.portId = Objects.requireNonNull(portId, "portId");
        this.initialBattery = BatteryPercent.starting(Objects.requireNonNull(initialBattery, "initialBattery").value());
        this.battery = initialBattery;
        this.tariff = Objects.requireNonNull(tariff, "tariff");
    }

    public void acknowledgeStarted() {
        require(SessionStatus.START_REQUESTED);
        status = SessionStatus.CHARGING;
    }

    public void recordBattery(BatteryPercent next) {
        if (status != SessionStatus.CHARGING && status != SessionStatus.STOP_REQUESTED)
            throw new IllegalStateException("Session cannot receive battery telemetry in " + status);
        Objects.requireNonNull(next, "battery");
        if (next.value() < battery.value()) throw new IllegalArgumentException("Battery cannot decrease");
        battery = next;
        if (next.full()) requestStop();
    }

    public void requestStop() {
        if (status == SessionStatus.STOP_REQUESTED) return;
        if (status != SessionStatus.START_REQUESTED && status != SessionStatus.CHARGING)
            throw new IllegalStateException("Cannot request stop in " + status);
        status = SessionStatus.STOP_REQUESTED;
    }

    /** Call only for the matching simulator stop acknowledgement. Duplicate delivery freezes the first fee. */
    public void acknowledgeStopped(EnergyKwh deliveredEnergy) {
        Objects.requireNonNull(deliveredEnergy, "deliveredEnergy");
        if (status == SessionStatus.COMPLETED) return;
        require(SessionStatus.STOP_REQUESTED);
        energy = deliveredEnergy;
        totalFee = tariff.charge(deliveredEnergy);
        status = SessionStatus.COMPLETED;
    }

    private void require(SessionStatus expected) {
        if (status != expected) throw new IllegalStateException("Expected " + expected + ", got " + status);
    }

    public UUID id() { return id; }
    public UUID bookingId() { return bookingId; }
    public UUID portId() { return portId; }
    public BatteryPercent initialBattery() { return initialBattery; }
    public BatteryPercent battery() { return battery; }
    public SessionStatus status() { return status; }
    public EnergyKwh energy() { return energy; }
    public Tariff tariff() { return tariff; }
    public Money totalFee() { return totalFee; }
}
