package vn.edu.campuscharge.incident;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class IncidentTest {
 @Test void rejectsMissingDetails() {
  assertThrows(IllegalArgumentException.class,()->new Incident(UUID.randomUUID(),UUID.randomUUID()," ",Instant.now()));
 }
}
