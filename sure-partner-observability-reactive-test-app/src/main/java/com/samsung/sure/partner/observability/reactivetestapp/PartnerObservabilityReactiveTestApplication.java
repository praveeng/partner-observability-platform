package com.samsung.sure.partner.observability.reactivetestapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Local-only reactive fixture used by the B003 callback and cancellation profiles. */
@SpringBootApplication
public class PartnerObservabilityReactiveTestApplication {
    public static void main(String[] args) {
        SpringApplication.run(PartnerObservabilityReactiveTestApplication.class, args);
    }
}
