package com.samsung.sure.partner.observability.testapp;

import static org.assertj.core.api.Assertions.assertThat;

import com.samsung.sure.partner.observability.autoconfigure.PartnerObservabilityProperties;
import com.samsung.sure.partner.observability.autoconfigure.PartnerObservations;
import com.samsung.sure.partner.observability.autoconfigure.callback.CallbackObservations;
import com.samsung.sure.partner.observability.core.context.PartnerContext;
import com.samsung.sure.partner.observability.core.health.TelemetryHealth;
import com.samsung.sure.partner.observability.testapp.client.OkHttpFixtureClient;
import com.samsung.sure.partner.observability.testapp.client.RestTemplateFixtureClient;
import com.samsung.sure.partner.observability.testapp.client.WebClientFixtureClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Compiles and starts exactly as a one-starter Spring Boot 2.7 partner-service consumer. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class EnterpriseNamespaceStarterConsumerIntegrationTest {

    @Autowired PartnerObservabilityProperties properties;
    @Autowired PartnerObservations observations;
    @Autowired CallbackObservations callbackObservations;
    @Autowired TelemetryHealth health;
    @Autowired RestTemplateFixtureClient restTemplateClient;
    @Autowired WebClientFixtureClient webClientFixtureClient;
    @Autowired OkHttpFixtureClient okHttpFixtureClient;
    @Autowired MeterRegistry meterRegistry;

    @Test
    void oneStarterExposesOnlyTheSamsungSureConsumerNamespaceAndLoadsIntegrations() {
        assertThat(properties.isEnabled()).isTrue();
        assertThat(observations).isNotNull();
        assertThat(callbackObservations).isNotNull();
        assertThat(health).isNotNull();
        assertThat(restTemplateClient).isNotNull();
        assertThat(webClientFixtureClient).isNotNull();
        assertThat(okHttpFixtureClient).isNotNull();
        assertThat(meterRegistry.find("partner_observability_http_interactions_total").counters())
                .isNotEmpty();
        assertThat(PartnerContext.class.getPackageName())
                .startsWith("com.samsung.sure.partner.observability.");
    }
}
