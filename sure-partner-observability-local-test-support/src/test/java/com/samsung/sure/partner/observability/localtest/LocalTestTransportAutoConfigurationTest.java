package com.samsung.sure.partner.observability.localtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.samsung.sure.partner.observability.core.publish.TelemetryPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LocalTestTransportAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LocalTestTransportAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withPropertyValues(
                    "spring.profiles.active=local",
                    "partner-observability.environment=local",
                    "partner-observability.local-synthetic=true",
                    "partner-observability.partners[0].key=partner-selected-fixture",
                    "partner-observability.local-test-transport.enabled=true",
                    "partner-observability.local-test-transport.endpoint=http://tenant-gateway:8080/v1/logs",
                    "partner-observability.local-test-transport.username=sdk-selected",
                    "partner-observability.local-test-transport.password=SYNTHETIC-LOCAL-ONLY",
                    "partner-observability.local-test-transport.fixed-partner-key=partner-selected-fixture");

    @Test
    void createsOneFixedPublisherOnlyForLocalSyntheticConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TelemetryPublisher.class);
            assertThat(context.getBean(TelemetryPublisher.class)).isInstanceOf(LocalTestOtlpTelemetryPublisher.class);
        });
    }

    @Test
    void neverActivatesUnderNonLocalProfile() {
        contextRunner.withPropertyValues("spring.profiles.active=dev")
                .run(context -> assertThat(context).doesNotHaveBean(TelemetryPublisher.class));
    }

    @Test
    void rejectsAProfileMixtureEvenWhenLocalIsPresent() {
        contextRunner.withPropertyValues("spring.profiles.active=local,dev")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsExternalOrUnconfiguredRoutes() {
        contextRunner.withPropertyValues(
                        "partner-observability.local-test-transport.endpoint=https://external.invalid/v1/logs")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues(
                        "partner-observability.local-test-transport.fixed-partner-key=partner-not-configured")
                .run(context -> assertThat(context).hasFailed());
    }
}
