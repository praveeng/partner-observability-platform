package com.samsung.sure.partner.observability.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keeps encrypted business integrations callable when observability is disabled. */
@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter(PartnerObservabilityAutoConfiguration.class)
@ConditionalOnProperty(prefix = "partner-observability", name = "enabled",
        havingValue = "false", matchIfMissing = true)
public class PartnerObservationsDisabledAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    PartnerObservations partnerObservations() {
        return PartnerObservations.noop();
    }
}
