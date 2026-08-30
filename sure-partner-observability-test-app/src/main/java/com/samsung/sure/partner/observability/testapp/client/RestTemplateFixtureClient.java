package com.samsung.sure.partner.observability.testapp.client;

import com.samsung.sure.partner.observability.testapp.fixture.LocalMockPartnerServer;
import com.samsung.sure.partner.observability.testapp.fixture.SyntheticPayloadFixtures;
import com.samsung.sure.partner.observability.testapp.model.ClientExchange;
import com.samsung.sure.partner.observability.testapp.model.SyntheticPartner;
import com.samsung.sure.partner.observability.testapp.model.SyntheticPartnerRequest;
import com.samsung.sure.partner.observability.testapp.model.SyntheticScenario;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public final class RestTemplateFixtureClient {

    private final RestTemplate restTemplate;
    private final LocalMockPartnerServer mockServer;
    private final SyntheticPayloadFixtures payloadFixtures;

    public RestTemplateFixtureClient(
            RestTemplate fixtureRestTemplate,
            LocalMockPartnerServer mockServer,
            SyntheticPayloadFixtures payloadFixtures) {
        this.restTemplate = fixtureRestTemplate;
        this.mockServer = mockServer;
        this.payloadFixtures = payloadFixtures;
    }

    public ClientExchange exchange(SyntheticScenario scenario, SyntheticPartner partner) {
        return exchange(scenario, partner, SyntheticPartnerRequest.COLLIDING_APPLICATION_ID);
    }

    public ClientExchange exchange(SyntheticScenario scenario, SyntheticPartner partner, String applicationId) {
        int maximumAttempts = scenario == SyntheticScenario.RETRY ? 2 : 1;
        ResponseEntity<String> response = null;
        for (int attempt = 1; attempt <= maximumAttempts; attempt++) {
            response = executeOnce(scenario, partner, applicationId, attempt);
            if (response.getStatusCodeValue() < 500 || attempt == maximumAttempts) {
                return new ClientExchange(
                        "rest-template",
                        scenario,
                        partner,
                        applicationId,
                        attempt,
                        response.getStatusCodeValue(),
                        response.getBody() == null ? "" : response.getBody());
            }
        }
        throw new IllegalStateException("SYNTHETIC_RETRY_STATE_INVALID");
    }

    private ResponseEntity<String> executeOnce(
            SyntheticScenario scenario, SyntheticPartner partner, String applicationId, int attempt) {
        URI endpoint = mockServer.partnerUri(scenario, partner, attempt);
        HttpHeaders headers = fixtureHeaders(scenario, partner, attempt);
        SyntheticPartnerRequest standard = SyntheticPartnerRequest.standard(partner, applicationId);
        Map<String, Object> attributes = new LinkedHashMap<>(standard.attributes());
        attributes.put("loanId", "SYNTHETIC-SYNC-LOAN-" + partner.name());
        attributes.put("correlationId", "SYNTHETIC-SYNC-CORRELATION-" + partner.name());
        if (scenario == SyntheticScenario.RESTRICTED_PII
                || scenario == SyntheticScenario.CREDENTIALS
                || scenario == SyntheticScenario.OTP
                || scenario == SyntheticScenario.CARD_DATA) {
            attributes.putAll(payloadFixtures.payloadFor(scenario, partner));
        }
        SyntheticPartnerRequest request = new SyntheticPartnerRequest(
                standard.applicationId(),
                standard.partnerReference(),
                standard.amount(),
                standard.tenureMonths(),
                standard.product(),
                attributes);
        return restTemplate.exchange(endpoint, HttpMethod.POST, new HttpEntity<>(request, headers), String.class);
    }

    static HttpHeaders fixtureHeaders(SyntheticScenario scenario, SyntheticPartner partner, int attempt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(LocalMockPartnerServer.PARTNER_HEADER, partner.name());
        headers.set(LocalMockPartnerServer.SCENARIO_HEADER, scenario.name());
        headers.set(LocalMockPartnerServer.ATTEMPT_HEADER, Integer.toString(attempt));
        return headers;
    }
}
