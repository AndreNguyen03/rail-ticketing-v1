package vn.railticketing.schedule;

import org.springframework.boot.SpringApplication;

public class TestScheduleServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(ScheduleServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
