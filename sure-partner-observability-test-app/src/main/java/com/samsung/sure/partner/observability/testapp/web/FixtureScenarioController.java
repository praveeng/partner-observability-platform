package com.samsung.sure.partner.observability.testapp.web;

import com.samsung.sure.partner.observability.testapp.client.OkHttpFixtureClient;
import com.samsung.sure.partner.observability.testapp.client.RestTemplateFixtureClient;
import com.samsung.sure.partner.observability.testapp.client.WebClientFixtureClient;
import com.samsung.sure.partner.observability.testapp.async.SyntheticAsyncFixtureClient;
import com.samsung.sure.partner.observability.testapp.async.SyntheticAsyncLifecycleStore;
import com.samsung.sure.partner.observability.testapp.async.SyntheticCallbackSecurityCounters;
import com.samsung.sure.partner.observability.testapp.crypto.EncryptedRestTemplateFixture;
import com.samsung.sure.partner.observability.testapp.crypto.EncryptedRoundTrip;
import com.samsung.sure.partner.observability.testapp.model.AsyncInitiationSummary;
import com.samsung.sure.partner.observability.testapp.model.ScenarioSummary;
import com.samsung.sure.partner.observability.testapp.model.SyntheticAsyncJourneySnapshot;
import com.samsung.sure.partner.observability.testapp.model.SyntheticAsyncScenario;
import com.samsung.sure.partner.observability.testapp.model.SyntheticPartner;
import com.samsung.sure.partner.observability.testapp.model.SyntheticPartnerRequest;
import com.samsung.sure.partner.observability.testapp.model.SyntheticScenario;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Loopback fixture control plane. Path values select predefined synthetic lanes; they are not a
 * production partner-context resolver and must never be reused as one.
 */
@RestController
@RequestMapping("/fixture")
public final class FixtureScenarioController {

    private final RestTemplateFixtureClient restTemplateClient;
    private final WebClientFixtureClient webClient;
    private final OkHttpFixtureClient okHttpClient;
    private final EncryptedRestTemplateFixture encryptedFixture;
    private final SyntheticAsyncFixtureClient asyncFixtureClient;
    private final SyntheticAsyncLifecycleStore asyncLifecycleStore;
    private final SyntheticCallbackSecurityCounters callbackSecurityCounters;

    public FixtureScenarioController(
            RestTemplateFixtureClient restTemplateClient,
            WebClientFixtureClient webClient,
            OkHttpFixtureClient okHttpClient,
            EncryptedRestTemplateFixture encryptedFixture,
            SyntheticAsyncFixtureClient asyncFixtureClient,
            SyntheticAsyncLifecycleStore asyncLifecycleStore,
            SyntheticCallbackSecurityCounters callbackSecurityCounters) {
        this.restTemplateClient = restTemplateClient;
        this.webClient = webClient;
        this.okHttpClient = okHttpClient;
        this.encryptedFixture = encryptedFixture;
        this.asyncFixtureClient = asyncFixtureClient;
        this.asyncLifecycleStore = asyncLifecycleStore;
        this.callbackSecurityCounters = callbackSecurityCounters;
    }

    @PostMapping("/rest/{partner}/{scenario}")
    public ScenarioSummary restTemplate(
            @PathVariable String partner, @PathVariable String scenario) {
        SyntheticPartner partnerLane = SyntheticPartner.fromFixturePath(partner);
        SyntheticScenario selectedScenario = SyntheticScenario.fromFixturePath(scenario);
        try {
            return ScenarioSummary.success(restTemplateClient.exchange(selectedScenario, partnerLane));
        } catch (RuntimeException exception) {
            return ScenarioSummary.failure(
                    "rest-template", selectedScenario, partnerLane, exception.getClass().getSimpleName());
        }
    }

    @PostMapping("/webclient/{partner}/{scenario}")
    public Mono<ScenarioSummary> webClient(
            @PathVariable String partner, @PathVariable String scenario) {
        SyntheticPartner partnerLane = SyntheticPartner.fromFixturePath(partner);
        SyntheticScenario selectedScenario = SyntheticScenario.fromFixturePath(scenario);
        return webClient
                .exchange(selectedScenario, partnerLane)
                .map(ScenarioSummary::success)
                .onErrorResume(exception -> Mono.just(ScenarioSummary.failure(
                        "web-client", selectedScenario, partnerLane, exception.getClass().getSimpleName())));
    }

    @PostMapping("/okhttp/{partner}/{scenario}")
    public ScenarioSummary okHttp(
            @PathVariable String partner, @PathVariable String scenario) {
        SyntheticPartner partnerLane = SyntheticPartner.fromFixturePath(partner);
        SyntheticScenario selectedScenario = SyntheticScenario.fromFixturePath(scenario);
        try {
            return ScenarioSummary.success(okHttpClient.exchange(selectedScenario, partnerLane));
        } catch (IOException exception) {
            return ScenarioSummary.failure("okhttp", selectedScenario, partnerLane, exception.getClass().getSimpleName());
        }
    }

    @PostMapping("/encrypted-rest/{partner}")
    public ScenarioSummary encryptedRestTemplate(@PathVariable String partner) {
        SyntheticPartner partnerLane = SyntheticPartner.fromFixturePath(partner);
        SyntheticPartnerRequest request = SyntheticPartnerRequest.standard(
                partnerLane, SyntheticPartnerRequest.COLLIDING_APPLICATION_ID);
        EncryptedRoundTrip result = encryptedFixture.roundTrip(partnerLane, request);
        return new ScenarioSummary(
                "rest-template-encrypted",
                "ENCRYPTED_ROUND_TRIP",
                partnerLane.name(),
                result.response().applicationId(),
                1,
                200,
                result.responseCiphertextBytes(),
                null,
                null);
    }

    @PostMapping("/async/{partner}/{scenario}")
    public AsyncInitiationSummary async(
            @PathVariable String partner,
            @PathVariable String scenario,
            HttpServletRequest request) {
        SyntheticPartner partnerLane = SyntheticPartner.fromFixturePath(partner);
        SyntheticAsyncScenario selectedScenario = SyntheticAsyncScenario.fromFixturePath(scenario);
        URI callbackRoot = URI.create(
                "http://127.0.0.1:" + request.getLocalPort() + request.getContextPath() + "/fixture/callback/");
        return asyncFixtureClient.initiate(selectedScenario, partnerLane, callbackRoot);
    }

    @GetMapping("/async/runs/{runId}")
    public SyntheticAsyncJourneySnapshot asyncRun(@PathVariable String runId) {
        return asyncLifecycleStore.snapshot(runId);
    }

    @GetMapping("/async/security-counters")
    public Map<SyntheticCallbackSecurityCounters.DenialReason, Long> callbackSecurityCounters() {
        return callbackSecurityCounters.snapshot();
    }
}
