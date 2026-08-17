package com.tsimafei.nexus_core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NexusCoreApplication {

	public static void main(String[] args) {
		SpringApplication.run(NexusCoreApplication.class, args);
	}
}