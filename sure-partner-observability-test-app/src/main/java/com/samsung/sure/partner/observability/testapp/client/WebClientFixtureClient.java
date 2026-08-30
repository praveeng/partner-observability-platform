package com.samsung.sure.partner.observability.testapp.client;

import com.samsung.sure.partner.observability.testapp.fixture.LocalMockPartnerServer;
import com.samsung.sure.partner.observability.testapp.model.ClientExchange;
import com.samsung.sure.partner.observability.testapp.model.SyntheticPartner;
import com.samsung.sure.partner.observability.testapp.model.SyntheticPartnerRequest;
import com.samsung.sure.partner.observability.testapp.model.SyntheticScenario;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public final class WebClientFixtureClient {

    private final WebClient webClient;
    private final LocalMockPartnerServer mockServer;

    public WebClientFixtureClient(WebClient fixtureWebClient, LocalMockPartnerServer mockServer) {
        this.webClient = fixtureWebClient;
        this.mockServer = mockServer;
    }

    public Mono<ClientExchange> exchange(SyntheticScenario scenario, SyntheticPartner partner) {
        return exchange(scenario, partner, SyntheticPartnerRequest.COLLIDING_APPLICATION_ID);
    }

    public Mono<ClientExchange> exchange(
            SyntheticScenario scenario, SyntheticPartner partner, String applicationId) {
        SyntheticPartnerRequest request = SyntheticPartnerRequest.standard(partner, applicationId);
        return webClient
                .post()
                .uri(mockServer.partnerUri(scenario, partner))
                .contentType(MediaType.APPLICATION_JSON)
                .header(LocalMockPartnerServer.PARTNER_HEADER, partner.name())
                .header(LocalMockPartnerServer.SCENARIO_HEADER, scenario.name())
                .header(LocalMockPartnerServer.ATTEMPT_HEADER, "1")
                .bodyValue(request)
                .exchangeToMono(response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new ClientExchange(
                                "web-client",
                                scenario,
                                partner,
                                applicationId,
                                1,
                                response.rawStatusCode(),
                                body)))
                .timeout(Duration.ofMillis(FixtureHttpClientsConfiguration.RESPONSE_TIMEOUT.toMillis()));
    }
}
