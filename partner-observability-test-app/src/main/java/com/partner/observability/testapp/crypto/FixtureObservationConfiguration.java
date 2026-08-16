package com.partner.observability.testapp.crypto;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class FixtureObservationConfiguration {

    @Bean
    @ConditionalOnMissingBean(FixturePlaintextObservationPort.class)
    FixturePlaintextObservationPort noOpFixturePlaintextObservationPort() {
        return new FixturePlaintextObservationPort() {
            @Override
            public void beforeEncryption(com.partner.observability.testapp.model.SyntheticPartnerRequest request) {
                // Production SDK integration will replace this fixture-only no-op bean.
            }

            @Override
            public void afterDecryption(com.partner.observability.testapp.model.SyntheticPartnerRequest response) {
                // Production SDK integration will replace this fixture-only no-op bean.
            }
        };
    }
}
