package com.samsung.sure.partner.observability.testapp;

import static org.assertj.core.api.Assertions.assertThat;

import com.samsung.sure.partner.observability.autoconfigure.PartnerObservabilityProperties;
import com.samsung.sure.partner.observability.core.context.DeploymentEnvironment;
import com.samsung.sure.partner.observability.core.dispatch.BoundedAsyncDispatcher;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

class EnterpriseProfileContextIntegrationTest {

    private static final List<String> CANONICAL_PROFILES = List.of("local", "dev", "stage", "prod");

    @TestFactory
    Stream<DynamicTest> canonicalProfilesStartAndBindInIsolation() {
        return CANONICAL_PROFILES.stream()
                .map(profile -> DynamicTest.dynamicTest(profile, () -> verifyProfile(profile)));
    }

    private void verifyProfile(String profile) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                        PartnerObservabilityTestApplication.class)
                .web(WebApplicationType.NONE)
                .run(arguments(profile))) {
            Environment springEnvironment = context.getEnvironment();
            PartnerObservabilityProperties properties = context.getBean(PartnerObservabilityProperties.class);

            assertThat(springEnvironment.getActiveProfiles()).containsExactly(profile);
            assertThat(properties.getEnvironment().canonicalValue()).isEqualTo(profile);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(context.getBeansOfType(BoundedAsyncDispatcher.class)).hasSize(1);
            assertThat(properties.getPartners()).hasSize(profile.equals("local") ? 2 : 1);
            assertThat(properties.getOutbound()).isNotEmpty()
                    .allSatisfy(definition -> {
                        if (profile.equals("local")) {
                            assertThat(definition.getOrigin()).isEqualTo("http://127.0.0.1");
                        } else {
                            assertThat(definition.getOrigin()).startsWith("https://");
                        }
                    });
            assertThat(properties.getCallbacks()).hasSize(profile.equals("local") ? 2 : 1);
            assertThat(properties.getCallbacks()).allSatisfy(callback -> {
                assertThat(callback.isProcessingEventsEnabled()).isTrue();
                assertThat(callback.getPath()).startsWith("/fixture/callback/");
            });
            assertThat(properties.isLocalSynthetic()).isEqualTo(profile.equals("local"));
            assertThat(springEnvironment.getProperty("local-synthetic.otlp.endpoint")).isNull();
            assertThat(springEnvironment.getProperty("server.address"))
                    .isEqualTo(profile.equals("local") ? "127.0.0.1" : null);
        }
    }

    private String[] arguments(String profile) {
        if (profile.equals("local")) {
            return new String[] {"--spring.profiles.active=local"};
        }
        String host = profile.equals("dev") ? "mock.dev.partner.invalid" : profile + ".partner.invalid";
        return new String[] {
            "--spring.profiles.active=" + profile,
            "--partner-observability.enabled=true",
            "--partner-observability.partners[0].key=profile-partner",
            "--partner-observability.partners[0].tenant-route-id=profile-tenant",
            "--partner-observability.partners[0].slot=p001",
            "--partner-observability.outbound[0].name=PROFILE_" + profile.toUpperCase() + "_API",
            "--partner-observability.outbound[0].origin=https://" + host,
            "--partner-observability.outbound[0].path=/applications",
            "--partner-observability.outbound[0].method=POST",
            "--partner-observability.outbound[0].partner=profile-partner",
            "--partner-observability.outbound[0].capture-mode=METADATA_ONLY",
            "--partner-observability.callbacks[0].name=PROFILE_" + profile.toUpperCase() + "_CALLBACK",
            "--partner-observability.callbacks[0].path=/fixture/callback/" + profile,
            "--partner-observability.callbacks[0].method=POST",
            "--partner-observability.callbacks[0].partner=profile-partner",
            "--partner-observability.callbacks[0].capture-mode=METADATA_ONLY",
            "--partner-observability.callbacks[0].processing-events-enabled=true"
        };
    }
}
