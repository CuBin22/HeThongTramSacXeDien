package vn.edu.campuscharge.station;

import java.util.Objects;
import java.util.UUID;

/** DISABLED is the admin maintenance lock; schedule conflicts are checked by allocation. */
public record ChargingPort(UUID id, UUID stationId, String connectorType, PortStatus status) {
    public ChargingPort {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(stationId, "stationId");
        Objects.requireNonNull(status, "status");
        if (connectorType == null || connectorType.isBlank()) throw new IllegalArgumentException("Connector type is required");
    }

    public boolean canReserve(String requestedConnector) {
        return status == PortStatus.AVAILABLE && connectorType.equals(requestedConnector);
    }
}
