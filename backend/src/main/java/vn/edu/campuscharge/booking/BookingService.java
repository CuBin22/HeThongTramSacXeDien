package vn.edu.campuscharge.booking;

import java.time.Instant;
import java.time.Duration;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.campuscharge.api.ApiAccess;
import vn.edu.campuscharge.booking.BookingRepository.BookingView;

@Service
@org.springframework.context.annotation.Profile("!simulator")
public class BookingService {
    private final BookingRepository repository;
    public BookingService(BookingRepository repository) { this.repository=repository; }
    public BookingView reserve(UUID user,UUID station,UUID port,UUID vehicle,Instant start,Instant end,String key) {
        ApiAccess.key(key);
        if (station==null || port==null || vehicle==null || start==null || end==null || !end.equals(start.plus(Duration.ofHours(4))))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Mỗi lượt đặt chỗ phải chọn đủ thông tin và giữ trụ đúng 4 giờ.");
        if (!start.isAfter(Instant.now()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Giờ đến phải nằm trong tương lai.");
        return repository.reserve(user,station,port,vehicle,start,end,key);
    }
    public List<BookingRepository.PortView> availablePorts(UUID user,UUID station,UUID vehicle,Instant start,Instant end) {
        if(station==null || vehicle==null || start==null || end==null || !end.equals(start.plus(Duration.ofHours(4))))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Khung đặt chỗ không hợp lệ.");
        if(!start.isAfter(Instant.now()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Giờ đến phải nằm trong tương lai.");
        return repository.availablePorts(user,station,vehicle,start,end);
    }
    @Transactional
    public BookingView cancel(UUID user,UUID id,String key) {
        ApiAccess.key(key);
        var booking=repository.find(id,true);
        ApiAccess.owner(booking.userId(),user);
        if (booking.status()!=BookingStatus.CANCELLED) {
            if (booking.status()!=BookingStatus.CONFIRMED) throw ApiAccess.conflict("Only confirmed bookings can be cancelled");
            repository.cancel(id);
        }
        return repository.find(id,false);
    }
    public BookingView get(UUID user,UUID id) { var result=repository.find(id,false); ApiAccess.owner(result.userId(),user); return result; }
    public List<BookingView> list(UUID user) { return repository.list(user); }
}
