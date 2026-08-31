package com.samsung.sure.partner.observability.localtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.samsung.sure.partner.observability.autoconfigure.PartnerObservabilityAutoConfiguration;
import com.samsung.sure.partner.observability.autoconfigure.PartnerObservabilityProperties;
import com.samsung.sure.partner.observability.core.context.DeploymentEnvironment;
import com.samsung.sure.partner.observability.core.publish.TelemetryPublisher;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Profile("local")
@AutoConfigureBefore(PartnerObservabilityAutoConfiguration.class)
@ConditionalOnClass(ObjectMapper.class)
@ConditionalOnProperty(
        prefix = "partner-observability.local-test-transport",
        name = "enabled",
        havingValue = "true")
@EnableConfigurationProperties({LocalTestTransportProperties.class, PartnerObservabilityProperties.class})
public class LocalTestTransportAutoConfiguration {
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]", "tenant-gateway");
    private static final Pattern SAFE_ROUTE_VALUE = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");

    @Bean
    @ConditionalOnMissingBean(TelemetryPublisher.class)
    TelemetryPublisher localTestTelemetryPublisher(
            ObjectMapper objectMapper,
            LocalTestTransportProperties transport,
            PartnerObservabilityProperties observability,
            Environment environment) {
        validate(transport, observability, environment.getActiveProfiles());
        return new LocalTestOtlpTelemetryPublisher(
                objectMapper,
                transport.getEndpoint(),
                transport.getUsername(),
                transport.getPassword(),
                transport.getFixedPartnerKey(),
                transport.getConnectTimeout(),
                transport.getRequestTimeout());
    }

    static void validate(
            LocalTestTransportProperties transport,
            PartnerObservabilityProperties observability,
            String[] activeProfiles) {
        if (activeProfiles.length != 1 || !"local".equals(activeProfiles[0])) {
            throw new IllegalStateException("LOCAL_TEST_TRANSPORT_REQUIRES_EXACT_LOCAL_PROFILE");
        }
        if (!observability.isLocalSynthetic() || observability.getEnvironment() != DeploymentEnvironment.LOCAL) {
            throw new IllegalStateException("LOCAL_TEST_TRANSPORT_REQUIRES_LOCAL_SYNTHETIC_ENVIRONMENT");
        }
        URI endpoint = transport.getEndpoint();
        if (endpoint == null
                || !"http".equalsIgnoreCase(endpoint.getScheme())
                || !LOCAL_HOSTS.contains(endpoint.getHost())
                || endpoint.getPort() < 1
                || !"/v1/logs".equals(endpoint.getPath())
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null) {
            throw new IllegalStateException("LOCAL_TEST_TRANSPORT_ENDPOINT_MUST_BE_FIXED_LOCAL_GATEWAY");
        }
        requireSafeRouteValue("USERNAME", transport.getUsername());
        requireSafeRouteValue("PARTNER_KEY", transport.getFixedPartnerKey());
        String password = transport.getPassword();
        if (password == null || password.isBlank() || password.length() > 256 || containsLineBreak(password)) {
            throw new IllegalStateException("LOCAL_TEST_TRANSPORT_PASSWORD_REQUIRED");
        }
        boolean configuredPartner = observability.getPartners().stream()
                .anyMatch(partner -> transport.getFixedPartnerKey().equals(partner.getKey()));
        if (!configuredPartner) {
            throw new IllegalStateException("LOCAL_TEST_TRANSPORT_PARTNER_MUST_BE_SERVER_CONFIGURED");
        }
        requireBoundedDuration("CONNECT_TIMEOUT", transport.getConnectTimeout());
        requireBoundedDuration("REQUEST_TIMEOUT", transport.getRequestTimeout());
    }

    private static void requireSafeRouteValue(String name, String value) {
        if (value == null || !SAFE_ROUTE_VALUE.matcher(value).matches()) {
            throw new IllegalStateException("LOCAL_TEST_TRANSPORT_INVALID_" + name);
        }
    }

    private static void requireBoundedDuration(String name, Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative() || duration.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalStateException("LOCAL_TEST_TRANSPORT_INVALID_" + name);
        }
    }

    private static boolean containsLineBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }
}
