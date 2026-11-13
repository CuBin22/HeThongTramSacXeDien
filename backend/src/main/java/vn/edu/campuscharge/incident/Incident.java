package vn.edu.campuscharge.incident;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A simulator fault report. A report never implies a charging session has stopped. */
public record Incident(UUID id, UUID portId, String description, Instant reportedAt) {
    public Incident {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(portId, "portId");
        Objects.requireNonNull(reportedAt, "reportedAt");
        if (description == null || description.isBlank()) throw new IllegalArgumentException("Incident description is required");
    }
}
