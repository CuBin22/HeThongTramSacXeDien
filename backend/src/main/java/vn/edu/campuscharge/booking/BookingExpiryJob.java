package vn.edu.campuscharge.booking;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@org.springframework.context.annotation.Profile("!simulator")
public class BookingExpiryJob {
    private final JdbcTemplate jdbc;

    public BookingExpiryJob(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    @Transactional
    public void expireMissedArrivals() {
        jdbc.update("""
            UPDATE bookings b SET status='EXPIRED'
            WHERE b.status='CONFIRMED' AND b.start_at + interval '15 minutes' <= now()
              AND NOT EXISTS (SELECT 1 FROM charging_sessions cs WHERE cs.booking_id=b.id)
            """);
    }
}
