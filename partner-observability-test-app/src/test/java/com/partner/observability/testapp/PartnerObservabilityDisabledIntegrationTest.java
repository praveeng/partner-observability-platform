package com.partner.observability.testapp;

import static org.assertj.core.api.Assertions.assertThat;

import com.partner.observability.core.dispatch.BoundedAsyncDispatcher;
import com.partner.observability.testapp.client.RestTemplateFixtureClient;
import com.partner.observability.testapp.crypto.EncryptedRestTemplateFixture;
import com.partner.observability.testapp.model.SyntheticPartner;
import com.partner.observability.testapp.model.SyntheticPartnerRequest;
import com.partner.observability.testapp.model.SyntheticScenario;
import com.partner.observability.testapp.telemetry.SyntheticTelemetryCollector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "partner-observability.enabled=false")
class PartnerObservabilityDisabledIntegrationTest {
    @Autowired RestTemplateFixtureClient restTemplate;
    @Autowired SyntheticTelemetryCollector telemetry;
    @Autowired EncryptedRestTemplateFixture encryptedFixture;
    @Autowired ApplicationContext context;

    @Test
    void starterDisabledLeavesServiceBehaviorUnchangedAndCreatesNoDispatcher() {
        telemetry.clear();
        assertThat(restTemplate.exchange(SyntheticScenario.SUCCESS, SyntheticPartner.ALPHA).httpStatus())
                .isEqualTo(200);
        SyntheticPartnerRequest encrypted = SyntheticPartnerRequest.standard(
                SyntheticPartner.BETA, "SYNTHETIC-DISABLED-ENCRYPTED");
        assertThat(encryptedFixture.roundTrip(SyntheticPartner.BETA, encrypted).response())
                .isEqualTo(encrypted);
        assertThat(telemetry.snapshot()).isEmpty();
        assertThat(context.getBeansOfType(BoundedAsyncDispatcher.class)).isEmpty();
    }
}
