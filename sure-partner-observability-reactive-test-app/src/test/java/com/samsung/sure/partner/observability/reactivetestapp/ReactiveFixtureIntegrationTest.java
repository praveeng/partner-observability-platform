package com.samsung.sure.partner.observability.reactivetestapp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
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
        properties = "local-synthetic.reactive.element-delay=1ms")
@AutoConfigureWebTestClient(timeout = "5s")
@ActiveProfiles("local")
class ReactiveFixtureIntegrationTest {
    private static final String FIXTURE_KEY = "local-synthetic-reactive-callback-key";

    @Autowired
    WebTestClient client;

    @Test
    void authenticatedReactiveCallbackCompletesWithoutLosingFixtureContext() {
        client.post()
                .uri("/fixture/reactive/callback/alpha?completion=inline")
                .header(ReactiveFixtureSecurityConfiguration.FIXTURE_KEY_HEADER, FIXTURE_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("fixtureClassification", "SYNTHETIC_ONLY", "marker", "SYNTHETIC-REACTIVE"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
                .expectBodyList(Map.class)
                .hasSize(1);

        Map<?, ?> metrics = client.get().uri("/fixture/reactive/metrics")
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        assertThat(metrics).isNotNull();
        assertThat(((Number) metrics.get("completed")).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) metrics.get("contextConflicts")).longValue()).isZero();
        assertThat(((Number) metrics.get("doubleSubscriptions")).longValue()).isZero();
        assertThat(((Number) metrics.get("doubleTerminalEvents")).longValue()).isZero();
        assertThat(((Number) metrics.get("active")).longValue()).isZero();
        assertThat(((Number) metrics.get("telemetryHighQueueEvents")).longValue())
                .isLessThanOrEqualTo(((Number) metrics.get("telemetryHighEventCap")).longValue());
        assertThat(((Number) metrics.get("telemetryNormalQueueEvents")).longValue())
                .isLessThanOrEqualTo(((Number) metrics.get("telemetryNormalEventCap")).longValue());
    }

    @Test
    void deferredReactiveCallbackAcknowledgesBeforeBackgroundCompletion() throws InterruptedException {
        Map<?, ?> before = metrics();
        long completedBefore = ((Number) before.get("completed")).longValue();

        client.post()
                .uri("/fixture/reactive/callback/beta?completion=short")
                .header(ReactiveFixtureSecurityConfiguration.FIXTURE_KEY_HEADER, FIXTURE_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "fixtureClassification", "SYNTHETIC_ONLY",
                        "marker", "SYNTHETIC-DEFERRED",
                        "applicationId", "SYNTHETIC-APPLICATION-DEFERRED",
                        "correlationId", "SYNTHETIC-CORRELATION-DEFERRED",
                        "callbackReferenceId", "SYNTHETIC-CALLBACK-DEFERRED"))
                .exchange()
                .expectStatus().isAccepted()
                .expectBodyList(Map.class)
                .hasSize(1)
                .consumeWith(result -> assertThat(result.getResponseBody())
                        .allSatisfy(value -> assertThat(value.get("correlationId"))
                                .isEqualTo("SYNTHETIC-CORRELATION-DEFERRED")));

        Map<?, ?> after = metrics();
        for (int attempt = 0; attempt < 20
                && ((Number) after.get("completed")).longValue() == completedBefore; attempt++) {
            Thread.sleep(50);
            after = metrics();
        }
        assertThat(((Number) after.get("completed")).longValue()).isGreaterThan(completedBefore);
        assertThat(((Number) after.get("active")).longValue()).isZero();
        assertThat(((Number) after.get("deferredActive")).longValue()).isZero();
        assertThat(((Number) after.get("maximumDeferredActive")).longValue())
                .isBetween(1L, ((Number) after.get("deferredCapacity")).longValue());
        assertThat(((Number) after.get("contextConflicts")).longValue()).isZero();
    }

    @Test
    void authenticatedReactiveStreamEmitsTheApprovedElementCount() {
        client.post()
                .uri("/fixture/reactive/stream/alpha")
                .header(ReactiveFixtureSecurityConfiguration.FIXTURE_KEY_HEADER, FIXTURE_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("fixtureClassification", "SYNTHETIC_ONLY", "marker", "SYNTHETIC-STREAM"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
                .expectBodyList(Map.class)
                .hasSize(32)
                .consumeWith(result -> assertThat(result.getResponseBody()).allSatisfy(element ->
                        assertThat(element.toString().length()).isGreaterThan(1900)));
    }

    @Test
    void syntheticLargeBase64CallbackCompletesWithoutEnteringAnUnboundedTelemetryQueue() {
        String document = Base64.getEncoder().encodeToString(new byte[5 * 1024 * 1024]);
        client.post()
                .uri("/fixture/reactive/callback/alpha?completion=inline")
                .header(ReactiveFixtureSecurityConfiguration.FIXTURE_KEY_HEADER, FIXTURE_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "fixtureClassification", "SYNTHETIC_ONLY",
                        "documentBase64", document))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Map.class)
                .hasSize(1);

        Map<?, ?> metrics = client.get().uri("/fixture/reactive/metrics")
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        assertThat(metrics).isNotNull();
        assertThat(((Number) metrics.get("active")).longValue()).isZero();
        assertThat(((Number) metrics.get("telemetryHighQueueBytes")).longValue())
                .isLessThanOrEqualTo(((Number) metrics.get("telemetryHighByteCap")).longValue());
        assertThat(((Number) metrics.get("telemetryNormalQueueBytes")).longValue())
                .isLessThanOrEqualTo(((Number) metrics.get("telemetryNormalByteCap")).longValue());
    }

    @Test
    void invalidFixtureCredentialCannotEstablishPartnerContext() {
        client.post()
                .uri("/fixture/reactive/callback/beta?completion=inline")
                .header(ReactiveFixtureSecurityConfiguration.FIXTURE_KEY_HEADER, "wrong-synthetic-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("fixtureClassification", "SYNTHETIC_ONLY"))
                .exchange()
                .expectStatus().is5xxServerError();

        Map<?, ?> metrics = client.get().uri("/fixture/reactive/metrics")
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        assertThat(metrics).isNotNull();
        assertThat(((Number) metrics.get("contextConflicts")).longValue()).isGreaterThanOrEqualTo(1);
    }

    private Map<?, ?> metrics() {
        Map<?, ?> result = client.get().uri("/fixture/reactive/metrics")
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        assertThat(result).isNotNull();
        return result;
    }
}
