package com.partner.observability.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.partner.observability.core.dispatch.BoundedAsyncDispatcher;
import com.partner.observability.core.policy.PayloadCaptureMode;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PartnerObservabilityAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PartnerObservabilityAutoConfiguration.class));

    @Test
    void disabledStarterCreatesNoRuntimeIntegrationBeans() {
        runner.withPropertyValues("partner-observability.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(BoundedAsyncDispatcher.class);
            assertThat(context).doesNotHaveBean(PartnerObservationEngine.class);
        });
    }

    @Test
    void oneConfigurationCreatesValidatedBoundedRuntime() {
        runner.withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues(validProperties())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(BoundedAsyncDispatcher.class);
                    assertThat(context).hasSingleBean(PartnerObservationEngine.class);
                    ConfiguredObservationRegistry registry = context.getBean(ConfiguredObservationRegistry.class);
                    assertThat(registry.outboundDefinitions()).singleElement()
                            .satisfies(definition -> {
                                assertThat(definition.captureMode()).isEqualTo(PayloadCaptureMode.METADATA_ONLY);
                                assertThat(definition.partnerContext().canonicalPartnerKey()).isEqualTo("partner-a");
                            });
                });
    }

    @Test
    void selectedLogCompatibilityRemainsOffByDefault() {
        runner.withPropertyValues(validProperties()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean("partnerLogbackBridge");
        });
    }

    @Test
    void invalidFullCaptureAndUnknownPartnerFailStartupClosed() {
        runner.withPropertyValues(
                        "partner-observability.enabled=true",
                        "partner-observability.service-name=fixture-service",
                        "partner-observability.service-version=1.0",
                        "partner-observability.market=synthetic",
                        "partner-observability.partners[0].key=partner-a",
                        "partner-observability.partners[0].tenant-route-id=tenant-a",
                        "partner-observability.partners[0].slot=p001",
                        "partner-observability.outbound[0].name=submit",
                        "partner-observability.outbound[0].path=/partner/a",
                        "partner-observability.outbound[0].partner=partner-missing",
                        "partner-observability.outbound[0].capture-mode=FULL_SANITIZED")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void logsEnabledWithoutAnExactSelectionFailsStartupClosed() {
        runner.withPropertyValues(
                        "partner-observability.enabled=true",
                        "partner-observability.logs-enabled=true",
                        "partner-observability.service-name=fixture-service",
                        "partner-observability.service-version=1.0",
                        "partner-observability.market=synthetic")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void loggerPatternWithoutAnExactMessageTemplateFailsStartupClosed() {
        String[] loggerOnly = java.util.Arrays.stream(validLogProperties())
                .filter(value -> !value.contains("message-template"))
                .toArray(String[]::new);
        runner.withPropertyValues(loggerOnly).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void missingOptionalLogbackClassesLeaveTheApplicationContextHealthy() {
        runner.withClassLoader(new FilteredClassLoader("ch.qos.logback"))
                .withPropertyValues(validLogProperties())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("partnerLogbackBridge");
                });
    }

    @Test
    void noPayloadModeCreatesMetricsObservationWithoutPartnerRecordCapture() {
        String[] properties = validProperties();
        properties[properties.length - 1] = "partner-observability.outbound[0].capture-mode=NO_PAYLOAD";
        runner.withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues(properties)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PartnerObservationEngine engine = context.getBean(PartnerObservationEngine.class);
                    assertThat(engine.startOutbound(
                            URI.create("http://127.0.0.1/partner/a"), "POST", null, false,
                            "application/json", java.util.OptionalLong.empty(), 1)).isPresent();
                });
    }

    private String[] validProperties() {
        return new String[] {
            "partner-observability.enabled=true",
            "partner-observability.service-name=fixture-service",
            "partner-observability.service-version=1.0",
            "partner-observability.market=synthetic",
            "partner-observability.partners[0].key=partner-a",
            "partner-observability.partners[0].tenant-route-id=tenant-a",
            "partner-observability.partners[0].slot=p001",
            "partner-observability.outbound[0].name=submit",
            "partner-observability.outbound[0].path=/partner/a",
            "partner-observability.outbound[0].partner=partner-a",
            "partner-observability.outbound[0].capture-mode=METADATA_ONLY"
        };
    }

    private String[] validLogProperties() {
        return new String[] {
            "partner-observability.enabled=true",
            "partner-observability.logs-enabled=true",
            "partner-observability.service-name=fixture-service",
            "partner-observability.service-version=1.0",
            "partner-observability.market=synthetic",
            "partner-observability.partners[0].key=partner-a",
            "partner-observability.partners[0].tenant-route-id=tenant-a",
            "partner-observability.partners[0].slot=p001",
            "partner-observability.log-selections[0].category=APPROVED_LOG",
            "partner-observability.log-selections[0].logger-pattern=com.synthetic.partner.**",
            "partner-observability.log-selections[0].message-template=Selected operation {}",
            "partner-observability.log-selections[0].journey-stage=LOG_EVENT"
        };
    }
}
