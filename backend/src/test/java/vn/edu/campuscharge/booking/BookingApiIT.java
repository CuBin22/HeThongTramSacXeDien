package vn.edu.campuscharge.booking;

import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import vn.edu.campuscharge.api.ApiTestSupport;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

class BookingApiIT extends ApiTestSupport {
    @Test void allocatesPortAutomaticallyAndReplaysReservation() throws Exception {
        UUID booking = reserve("reserve");
        mvc.perform(post("/api/bookings").with(authentication(owner())).with(csrf()).header("Idempotency-Key","reserve").contentType("application/json").content(bookingJson()))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.bookingId").value(booking.toString()))
            .andExpect(jsonPath("$.portId").value(PORT.toString()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings", Integer.class)).isEqualTo(1);
    }
    @Test void rejectsClientSelectedPortAndMismatchedReplay() throws Exception {
        mvc.perform(post("/api/bookings").with(authentication(owner())).with(csrf()).header("Idempotency-Key","port").contentType("application/json")
            .content(bookingJson().replace("}",",\"portId\":\""+PORT+"\"}"))).andExpect(status().isBadRequest());
        reserve("reserve");
        mvc.perform(post("/api/bookings").with(authentication(owner())).with(csrf()).header("Idempotency-Key","reserve").contentType("application/json")
            .content(bookingJson().replace("2030-01-01T12:00:00Z","2030-01-01T13:00:00Z"))).andExpect(status().isConflict());
    }
    @Test void rejectsUnavailableStationAndMissingIdempotencyKey() throws Exception {
        jdbc.update("UPDATE stations SET active=false WHERE id=?", STATION);
        mvc.perform(post("/api/bookings").with(authentication(owner())).with(csrf()).header("Idempotency-Key","inactive").contentType("application/json")
            .content(bookingJson())).andExpect(status().isConflict());
        mvc.perform(post("/api/bookings").with(authentication(owner())).with(csrf()).contentType("application/json").content(bookingJson()))
            .andExpect(status().isBadRequest());
    }
    @Test void cancellationReleasesPortAndCannotCancelAnotherUsersBooking() throws Exception {
        UUID booking=reserve("reserve");
        mvc.perform(post("/api/bookings/"+booking+"/cancel").with(authentication(other())).with(csrf()).header("Idempotency-Key","cancel"))
            .andExpect(status().isForbidden());
        for (int i=0;i<2;i++) mvc.perform(post("/api/bookings/"+booking+"/cancel").with(authentication(owner())).with(csrf()).header("Idempotency-Key","cancel"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        reserve("replacement");
        mvc.perform(get("/api/bookings").with(authentication(other()))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/stations").with(authentication(owner()))).andExpect(status().isOk()).andExpect(jsonPath("$[0].stationId").value(STATION.toString()));
        mvc.perform(get("/api/stations/"+STATION+"/ports").with(authentication(owner()))).andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("RESERVED"));
    }
    @Test void concurrentRequestsForLastPortReserveItExactlyOnce() throws Exception {
        try (var pool=Executors.newFixedThreadPool(2)) {
            var barrier=new CyclicBarrier(2);
            Callable<Integer> first=()->{barrier.await(); return reserveStatus("a");};
            Callable<Integer> second=()->{barrier.await(); return reserveStatus("b");};
            var a=pool.submit(first); var b=pool.submit(second);
            assertThat(java.util.List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings",Integer.class)).isEqualTo(1);
    }
    private int reserveStatus(String key) throws Exception {
        return mvc.perform(post("/api/bookings").with(authentication(owner())).with(csrf()).header("Idempotency-Key",key).contentType("application/json").content(bookingJson()))
            .andReturn().getResponse().getStatus();
    }
}
