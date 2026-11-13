package vn.edu.campuscharge.charging;

/** An integer percentage reported by the software charger simulator. */
public record BatteryPercent(int value) {
    public BatteryPercent {
        if (value < 0 || value > 100) throw new IllegalArgumentException("Battery must be 0–100 percent");
    }

    public static BatteryPercent starting(int value) {
        if (value == 100) throw new IllegalArgumentException("Cannot start with a full battery");
        return new BatteryPercent(value);
    }

    public boolean nearFull() { return value >= 90; }
    public boolean full() { return value == 100; }
}
