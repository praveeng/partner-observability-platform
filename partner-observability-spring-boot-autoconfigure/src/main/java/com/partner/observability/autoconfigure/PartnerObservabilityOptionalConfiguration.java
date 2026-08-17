package com.partner.observability.autoconfigure;

import com.partner.observability.core.health.TelemetryHealth;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import({
    PartnerObservabilityOptionalConfiguration.MetricsConfiguration.class,
    PartnerObservabilityOptionalConfiguration.ActuatorConfiguration.class
})
public class PartnerObservabilityOptionalConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnBean(MeterRegistry.class)
    static class MetricsConfiguration {
        @Bean
        @ConditionalOnMissingBean(ObservationMetrics.class)
        ObservationMetrics micrometerPartnerObservationMetrics(
                MeterRegistry registry, PartnerObservabilityProperties properties) {
            return properties.isMetricsEnabled()
                    ? new MicrometerObservationMetrics(registry, properties.getServiceName())
                    : ObservationMetrics.NONE;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HealthIndicator.class)
    static class ActuatorConfiguration {
        @Bean(name = "partnerObservabilityHealthIndicator")
        @ConditionalOnMissingBean(name = "partnerObservabilityHealthIndicator")
        HealthIndicator partnerObservabilityHealthIndicator(
                TelemetryHealth telemetryHealth, PartnerObservabilityProperties properties) {
            return () -> {
                var snapshot = telemetryHealth.snapshot();
                return Health.up()
                        .withDetail("enabled", properties.isEnabled())
                        .withDetail("state", snapshot.state().name())
                        .withDetail("dispatcherAlive", snapshot.dispatcherAlive())
                        .withDetail("queueEvents", snapshot.highQueueEvents() + snapshot.normalQueueEvents())
                        .withDetail("queueBytes", snapshot.highQueueBytes() + snapshot.normalQueueBytes())
                        .withDetail("captureAttempts", snapshot.captureAttempts())
                        .withDetail("enqueued", snapshot.enqueued())
                        .withDetail("drops", snapshot.totalDrops())
                        .build();
            };
        }
    }
}
