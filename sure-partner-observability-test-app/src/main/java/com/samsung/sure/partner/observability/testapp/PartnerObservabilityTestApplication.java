package com.samsung.sure.partner.observability.testapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Loopback-only application containing synthetic partner integration scenarios. */
@SpringBootApplication
public class PartnerObservabilityTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(PartnerObservabilityTestApplication.class, args);
    }
}
