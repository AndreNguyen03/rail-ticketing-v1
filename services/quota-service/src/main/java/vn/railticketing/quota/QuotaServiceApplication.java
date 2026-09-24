package vn.railticketing.quota;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class QuotaServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(QuotaServiceApplication.class, args);
    }
}
