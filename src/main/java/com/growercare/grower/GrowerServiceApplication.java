package com.growercare.grower;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.growercare")
public class GrowerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(GrowerServiceApplication.class, args);
    }
}
