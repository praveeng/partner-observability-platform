package com.samsung.sure.partner.observability.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.samsung.sure.partner.observability.autoconfigure.callback.CallbackInstrumentationConfiguration;
import com.samsung.sure.partner.observability.autoconfigure.http.HttpClientInstrumentationConfiguration;
import com.samsung.sure.partner.observability.autoconfigure.logging.PartnerLogbackConfiguration;
import com.samsung.sure.partner.observability.core.dispatch.BoundedAsyncDispatcher;
import com.samsung.sure.partner.observability.core.dispatch.DispatcherConfig;
import com.samsung.sure.partner.observability.core.health.TelemetryHealth;
import com.samsung.sure.partner.observability.core.payload.FailClosedPayloadSanitizer;
import com.samsung.sure.partner.observability.core.policy.KillSwitchState;
import com.samsung.sure.partner.observability.core.policy.ObservabilityKillSwitches;
import com.samsung.sure.partner.observability.core.publish.TelemetryPublisher;
import com.samsung.sure.partner.observability.core.time.SystemTimeSource;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskDecorator;

@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter(name = {
    "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
    "org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration",
    "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration"
})
@EnableConfigurationProperties(PartnerObservabilityProperties.class)
@ConditionalOnProperty(prefix = "partner-observability", name = "enabled", havingValue = "true")
@Import({
    HttpClientInstrumentationConfiguration.class,
    CallbackInstrumentationConfiguration.class,
    PartnerLogbackConfiguration.class,
    PartnerObservabilityOptionalConfiguration.class
})
public class PartnerObservabilityAutoConfiguration {

    @Bean
    PartnerObservabilityConfigurationValidator partnerObservabilityConfigurationValidator(
            PartnerObservabilityProperties properties) {
        PartnerObservabilityConfigurationValidator validator = new PartnerObservabilityConfigurationValidator();
        validator.validate(properties);
        return validator;
    }

    @Bean
    ConfiguredObservationRegistry configuredObservationRegistry(
            PartnerObservabilityProperties properties,
            PartnerObservabilityConfigurationValidator ignored) {
        return new ConfiguredObservationRegistry(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    TelemetryPublisher partnerObservabilityTelemetryPublisher() {
        return batch -> {
            // Safe default: discard after bounded asynchronous handoff until a transport is configured.
        };
    }

    @Bean
    TelemetryHealth partnerObservabilityTelemetryHealth() {
        return new TelemetryHealth(SystemTimeSource.INSTANCE);
    }

    @Bean
    ObservabilityKillSwitches partnerObservabilityKillSwitches(PartnerObservabilityProperties properties) {
        return new ObservabilityKillSwitches(new KillSwitchState(
                properties.isEnabled(), properties.isPayloadsEnabled(), properties.isLogsEnabled(),
                properties.isEventsEnabled(), properties.isMetricsEnabled(), properties.isExportEnabled()));
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    BoundedAsyncDispatcher partnerObservabilityDispatcher(
            TelemetryPublisher publisher,
            ObservabilityKillSwitches killSwitches,
            TelemetryHealth health) {
        return new BoundedAsyncDispatcher(publisher, DispatcherConfig.defaults(), killSwitches, health);
    }

    @Bean
    FailClosedPayloadSanitizer partnerObservabilityPayloadSanitizer() {
        return new FailClosedPayloadSanitizer();
    }

    @Bean
    @ConditionalOnMissingBean(TaskDecorator.class)
    TaskDecorator partnerObservationTaskDecorator() {
        return new PartnerObservationTaskDecorator();
    }

    @Bean
    PartnerObservationEngine partnerObservationEngine(
            PartnerObservabilityProperties properties,
            ConfiguredObservationRegistry registry,
            ObservabilityKillSwitches killSwitches,
            BoundedAsyncDispatcher dispatcher,
            ObjectProvider<SafeBodyCapture> bodyCapture,
            ObjectProvider<ObservationMetrics> metrics) {
        return new PartnerObservationEngine(
                properties, registry, killSwitches, dispatcher,
                Optional.ofNullable(bodyCapture.getIfAvailable()),
                metrics.getIfAvailable(() -> ObservationMetrics.NONE));
    }

    @Bean
    @ConditionalOnMissingBean
    PartnerObservations partnerObservations(
            PartnerObservabilityProperties properties,
            ConfiguredObservationRegistry registry,
            PartnerObservationEngine engine,
            FailClosedPayloadSanitizer sanitizer,
            ObjectProvider<PartnerPlaintextSchema<?>> schemas) {
        return new PartnerObservations(
                properties,
                registry,
                engine,
                sanitizer,
                schemas.orderedStream()
                        .limit(PartnerObservations.MAX_PLAINTEXT_SCHEMAS + 1L)
                        .toList());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ObjectMapper.class)
    @ConditionalOnBean(ObjectMapper.class)
    static class JacksonCaptureConfiguration {
        @Bean
        @ConditionalOnMissingBean
        SafeBodyCapture partnerObservabilitySafeBodyCapture(
                ObjectMapper objectMapper,
                FailClosedPayloadSanitizer sanitizer,
                ObjectProvider<CorrelationIdentifiersExtractor> extractors) {
            return new JacksonSafeBodyCapture(objectMapper, sanitizer, extractors.orderedStream().toList());
        }
    }
}
