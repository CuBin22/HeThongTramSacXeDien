package vn.edu.campuscharge.station;

import java.util.Objects;
import java.util.UUID;

public record Station(UUID id, String name, boolean active) {
    public Station {
        Objects.requireNonNull(id, "id");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Station name is required");
    }
}
