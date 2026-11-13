package vn.edu.campuscharge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class CampusChargeApplication {
    public static void main(String[] args) {
        SpringApplication.run(CampusChargeApplication.class, args);
    }
}
