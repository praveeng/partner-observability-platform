package com.partner.observability.autoconfigure.logging;

import com.partner.observability.autoconfigure.ConfiguredObservationRegistry;
import com.partner.observability.autoconfigure.PartnerObservabilityProperties;
import com.partner.observability.core.dispatch.BoundedAsyncDispatcher;
import com.partner.observability.core.health.TelemetryHealth;
import com.partner.observability.core.payload.FailClosedPayloadSanitizer;
import com.partner.observability.core.policy.ObservabilityKillSwitches;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Optional Logback compatibility bridge. Its classpath and property guards keep the disabled path
 * free of Logback work and preserve services using another SLF4J backend.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = {
    "ch.qos.logback.classic.LoggerContext",
    "ch.qos.logback.classic.spi.ILoggingEvent"
})
@ConditionalOnProperty(
        prefix = "partner-observability",
        name = "logs-enabled",
        havingValue = "true")
public class PartnerLogbackConfiguration {

    @Bean
    PartnerSafeLogCapture partnerSafeLogCapture(
            PartnerObservabilityProperties properties,
            ConfiguredObservationRegistry observationRegistry,
            ObservabilityKillSwitches killSwitches,
            BoundedAsyncDispatcher dispatcher,
            TelemetryHealth health,
            FailClosedPayloadSanitizer sanitizer) {
        return new PartnerSafeLogCapture(
                properties, observationRegistry, killSwitches, dispatcher, health, sanitizer);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    PartnerLogbackBridge partnerLogbackBridge(PartnerSafeLogCapture capture) {
        return new PartnerLogbackBridge(capture);
    }
}
