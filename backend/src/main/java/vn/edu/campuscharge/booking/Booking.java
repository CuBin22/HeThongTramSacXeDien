package vn.edu.campuscharge.booking;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Reservation of an assigned port for a half-open [startAt, endAt) interval. */
public final class Booking {
    private final UUID id;
    private final UUID userId;
    private final UUID portId;
    private final Instant startAt;
    private final Instant endAt;
    private BookingStatus status = BookingStatus.CONFIRMED;

    public Booking(UUID id, UUID userId, UUID portId, Instant startAt, Instant endAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.portId = Objects.requireNonNull(portId, "portId");
        this.startAt = Objects.requireNonNull(startAt, "startAt");
        this.endAt = Objects.requireNonNull(endAt, "endAt");
        if (!endAt.isAfter(startAt)) throw new IllegalArgumentException("Booking end must follow start");
    }

    public void start() { transition(BookingStatus.CONFIRMED, BookingStatus.IN_USE); }
    public void complete() { transition(BookingStatus.IN_USE, BookingStatus.COMPLETED); }
    public void cancel() { transition(BookingStatus.CONFIRMED, BookingStatus.CANCELLED); }
    public void expire() { transition(BookingStatus.CONFIRMED, BookingStatus.EXPIRED); }
    public void markUnserviceable() { transition(BookingStatus.CONFIRMED, BookingStatus.UNSERVICEABLE); }

    private void transition(BookingStatus required, BookingStatus next) {
        if (status != required) throw new IllegalStateException("Cannot change booking from " + status + " to " + next);
        status = next;
    }

    public UUID id() { return id; }
    public UUID userId() { return userId; }
    public UUID portId() { return portId; }
    public Instant startAt() { return startAt; }
    public Instant endAt() { return endAt; }
    public BookingStatus status() { return status; }
}
