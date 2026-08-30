package com.samsung.sure.partner.observability.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.samsung.sure.partner.observability.core.dispatch.BoundedAsyncDispatcher;
import com.samsung.sure.partner.observability.core.policy.PayloadCaptureMode;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PartnerObservabilityAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PartnerObservabilityAutoConfiguration.class))
            .withPropertyValues("partner-observability.environment=dev");

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
                            URI.create("https://partner-a.example/partner/a"), "POST", null, false,
                            "application/json", java.util.OptionalLong.empty(), 1)).isPresent();
                });
    }

    @Test
    void outboundSelectionCannotCrossAConfiguredPartnerOrigin() {
        runner.withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues(validProperties())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ConfiguredObservationRegistry registry = context.getBean(ConfiguredObservationRegistry.class);

                    assertThat(registry.outbound("POST", URI.create("https://partner-a.example/partner/a")))
                            .isPresent();
                    assertThat(registry.outbound("POST", URI.create("https://partner-b.example/partner/a")))
                            .isEmpty();
                });
    }

    @Test
    void overlappingCallbackRoutesForDifferentPartnersFailStartupClosed() {
        runner.withPropertyValues(
                        "partner-observability.enabled=true",
                        "partner-observability.service-name=fixture-service",
                        "partner-observability.service-version=1.0",
                        "partner-observability.market=synthetic",
                        "partner-observability.partners[0].key=partner-a",
                        "partner-observability.partners[0].tenant-route-id=tenant-a",
                        "partner-observability.partners[0].slot=p001",
                        "partner-observability.partners[1].key=partner-b",
                        "partner-observability.partners[1].tenant-route-id=tenant-b",
                        "partner-observability.partners[1].slot=p002",
                        "partner-observability.callbacks[0].name=callback-a",
                        "partner-observability.callbacks[0].path=/callbacks/{applicationId}",
                        "partner-observability.callbacks[0].partner=partner-a",
                        "partner-observability.callbacks[1].name=callback-b",
                        "partner-observability.callbacks[1].path=/callbacks/{partnerReferenceId}",
                        "partner-observability.callbacks[1].partner=partner-b")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void plaintextOrMissingPartnerOriginFailsStartupClosed() {
        String[] missingOrigin = java.util.Arrays.stream(validProperties())
                .filter(value -> !value.contains(".origin="))
                .toArray(String[]::new);
        runner.withPropertyValues(missingOrigin).run(context -> assertThat(context).hasFailed());

        String[] plaintext = java.util.Arrays.stream(validProperties())
                .map(value -> value.contains(".origin=")
                        ? "partner-observability.outbound[0].origin=http://partner-a.example"
                        : value)
                .toArray(String[]::new);
        runner.withPropertyValues(plaintext).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void insecureLoopbackRequiresAnExplicitLocalOnlyException() {
        String[] loopback = java.util.Arrays.stream(validProperties())
                .map(value -> value.contains(".origin=")
                        ? "partner-observability.outbound[0].origin=http://127.0.0.1"
                        : value)
                .toArray(String[]::new);
        runner.withPropertyValues(loopback).run(context -> assertThat(context).hasFailed());

        runner.withPropertyValues(loopback)
                .withPropertyValues(
                        "partner-observability.local-synthetic=true",
                        "partner-observability.environment=local")
                .run(context -> assertThat(context).hasNotFailed());

        for (String environment : new String[] {"dev", "stage", "prod"}) {
            runner.withPropertyValues(loopback)
                    .withPropertyValues(
                            "partner-observability.local-synthetic=true",
                            "partner-observability.environment=" + environment)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void identicalPathsAtDifferentApprovedOriginsRemainPartnerIsolated() {
        runner.withPropertyValues(
                        "partner-observability.enabled=true",
                        "partner-observability.service-name=fixture-service",
                        "partner-observability.service-version=1.0",
                        "partner-observability.market=synthetic",
                        "partner-observability.partners[0].key=partner-a",
                        "partner-observability.partners[0].tenant-route-id=tenant-a",
                        "partner-observability.partners[0].slot=p001",
                        "partner-observability.partners[1].key=partner-b",
                        "partner-observability.partners[1].tenant-route-id=tenant-b",
                        "partner-observability.partners[1].slot=p002",
                        "partner-observability.outbound[0].name=submit-a",
                        "partner-observability.outbound[0].origin=https://partner-a.example",
                        "partner-observability.outbound[0].path=/applications",
                        "partner-observability.outbound[0].partner=partner-a",
                        "partner-observability.outbound[1].name=submit-b",
                        "partner-observability.outbound[1].origin=https://partner-b.example",
                        "partner-observability.outbound[1].path=/applications",
                        "partner-observability.outbound[1].partner=partner-b")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ConfiguredObservationRegistry registry = context.getBean(ConfiguredObservationRegistry.class);
                    assertThat(registry.outbound("POST", URI.create("https://partner-a.example/applications")))
                            .hasValueSatisfying(definition -> assertThat(
                                            definition.partnerContext().canonicalPartnerKey())
                                    .isEqualTo("partner-a"));
                    assertThat(registry.outbound("POST", URI.create("https://partner-b.example/applications")))
                            .hasValueSatisfying(definition -> assertThat(
                                            definition.partnerContext().canonicalPartnerKey())
                                    .isEqualTo("partner-b"));
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
            "partner-observability.outbound[0].origin=https://partner-a.example",
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
