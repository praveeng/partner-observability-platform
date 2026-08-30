package com.samsung.sure.partner.observability.reactivetestapp;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "partner-observability.enabled=false",
            "local-synthetic.reactive.element-delay=1ms"
        })
@AutoConfigureWebTestClient(timeout = "5s")
@ActiveProfiles("local")
class ReactiveFixtureDisabledIntegrationTest {
    @Autowired
    WebTestClient client;

    @Test
    void businessCallbackStillCompletesWhenObservabilityIsDisabled() {
        client.post()
                .uri("/fixture/reactive/callback/alpha?completion=inline")
                .header(
                        ReactiveFixtureSecurityConfiguration.FIXTURE_KEY_HEADER,
                        "local-synthetic-reactive-callback-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("fixtureClassification", "SYNTHETIC_ONLY"))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Map.class)
                .hasSize(1);
    }
}
