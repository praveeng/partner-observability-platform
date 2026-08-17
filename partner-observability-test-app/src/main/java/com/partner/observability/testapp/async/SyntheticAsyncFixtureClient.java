package com.partner.observability.testapp.async;

import com.partner.observability.testapp.fixture.LocalMockPartnerServer;
import com.partner.observability.testapp.model.AsyncInitiationSummary;
import com.partner.observability.testapp.model.SyntheticAsyncAcknowledgement;
import com.partner.observability.testapp.model.SyntheticAsyncRequest;
import com.partner.observability.testapp.model.SyntheticAsyncScenario;
import com.partner.observability.testapp.model.SyntheticCorrelationIdentifiers;
import com.partner.observability.testapp.model.SyntheticPartner;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/** Initiates asynchronous exchanges against the dedicated loopback mock partner. */
@Component
public final class SyntheticAsyncFixtureClient {

    private final RestTemplate restTemplate;
    private final LocalMockPartnerServer mockServer;
    private final SyntheticAsyncLifecycleStore lifecycleStore;

    public SyntheticAsyncFixtureClient(
            RestTemplate fixtureRestTemplate,
            LocalMockPartnerServer mockServer,
            SyntheticAsyncLifecycleStore lifecycleStore) {
        this.restTemplate = fixtureRestTemplate;
        this.mockServer = mockServer;
        this.lifecycleStore = lifecycleStore;
    }

    public AsyncInitiationSummary initiate(
            SyntheticAsyncScenario scenario, SyntheticPartner partner, URI callbackRoot) {
        String runId = UUID.randomUUID().toString();
        SyntheticAsyncRequest request = new SyntheticAsyncRequest(
                runId, partner, scenario, SyntheticCorrelationIdentifiers.initiation(runId));
        lifecycleStore.begin(request);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(LocalMockPartnerServer.PARTNER_HEADER, partner.name());
        headers.set(LocalMockPartnerServer.ASYNC_SCENARIO_HEADER, scenario.name());
        headers.set(LocalMockPartnerServer.CALLBACK_ROOT_HEADER, callbackRoot.toString());
        try {
            ResponseEntity<SyntheticAsyncAcknowledgement> response = restTemplate.exchange(
                    mockServer.asyncUri(partner),
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    SyntheticAsyncAcknowledgement.class);
            SyntheticAsyncAcknowledgement acknowledgement = response.getBody();
            if (response.getStatusCodeValue() != 202 || acknowledgement == null) {
                throw new IllegalStateException("SYNTHETIC_ACKNOWLEDGEMENT_INVALID");
            }
            lifecycleStore.acknowledgement(runId, response.getStatusCodeValue(), acknowledgement);
            return summary(runId);
        } catch (ResourceAccessException exception) {
            if (scenario != SyntheticAsyncScenario.CALLBACK_AFTER_OUTBOUND_TIMEOUT) {
                throw exception;
            }
            lifecycleStore.acknowledgementNotReceived(runId);
            return summary(runId);
        }
    }

    private AsyncInitiationSummary summary(String runId) {
        var snapshot = lifecycleStore.snapshot(runId);
        return new AsyncInitiationSummary(
                snapshot.runId(),
                snapshot.scenario(),
                snapshot.partner(),
                snapshot.acknowledgementHttpStatus(),
                snapshot.acknowledgementReceived(),
                snapshot.identifiers());
    }
}
