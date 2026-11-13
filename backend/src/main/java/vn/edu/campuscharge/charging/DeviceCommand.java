package vn.edu.campuscharge.charging;

import java.util.Objects;
import java.util.UUID;

/** Delivery lifecycle is separate from the charging session lifecycle. */
public final class DeviceCommand {
    public enum Type { START, STOP }

    private final UUID id;
    private final UUID portId;
    private final Type type;
    private final String idempotencyKey;
    private DeviceCommandStatus status = DeviceCommandStatus.PENDING;

    public DeviceCommand(UUID id, UUID portId, Type type, String idempotencyKey) {
        this.id = Objects.requireNonNull(id, "id");
        this.portId = Objects.requireNonNull(portId, "portId");
        this.type = Objects.requireNonNull(type, "type");
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new IllegalArgumentException("Idempotency key is required");
        this.idempotencyKey = idempotencyKey;
    }

    public void markSent() {
        require(DeviceCommandStatus.PENDING);
        status = DeviceCommandStatus.SENT;
    }

    public void acknowledge() {
        if (status == DeviceCommandStatus.ACKNOWLEDGED) return;
        if (status != DeviceCommandStatus.SENT && status != DeviceCommandStatus.TIMED_OUT)
            throw new IllegalStateException("Cannot acknowledge command in " + status);
        status = DeviceCommandStatus.ACKNOWLEDGED;
    }

    public void fail() {
        require(DeviceCommandStatus.SENT);
        status = DeviceCommandStatus.FAILED;
    }

    public void timeOut() {
        require(DeviceCommandStatus.SENT);
        status = DeviceCommandStatus.TIMED_OUT;
    }

    private void require(DeviceCommandStatus expected) {
        if (status != expected) throw new IllegalStateException("Expected " + expected + ", got " + status);
    }

    public UUID id() { return id; }
    public UUID portId() { return portId; }
    public Type type() { return type; }
    public String idempotencyKey() { return idempotencyKey; }
    public DeviceCommandStatus status() { return status; }
}
