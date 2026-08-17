package com.partner.observability.autoconfigure.callback;

import static org.assertj.core.api.Assertions.assertThat;

import com.partner.observability.autoconfigure.PartnerObservabilityAutoConfiguration;
import com.partner.observability.autoconfigure.PartnerObservationContext;
import com.partner.observability.core.model.CallbackRequestRecord;
import com.partner.observability.core.model.CallbackResponseRecord;
import com.partner.observability.core.model.TelemetryEnvelope;
import com.partner.observability.core.publish.TelemetryPublisher;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

class PartnerCallbackWebFilterTest {

    @Test
    void trustedReactiveCallbackUsesReactorContextAndEmitsSeparateMetadataRecords() {
        List<TelemetryEnvelope<?>> published = new CopyOnWriteArrayList<>();
        AtomicBoolean contextObserved = new AtomicBoolean();

        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PartnerObservabilityAutoConfiguration.class))
                .withBean(TelemetryPublisher.class, () -> batch -> batch.submissions().forEach(
                        submission -> published.add(submission.envelope())))
                .withBean(ReactiveCallbackPartnerKeyResolver.class,
                        () -> (exchange, callbackName) -> Mono.just(Optional.of("partner-a")))
                .withPropertyValues(
                        "partner-observability.enabled=true",
                        "partner-observability.service-name=reactive-fixture",
                        "partner-observability.service-version=1.0",
                        "partner-observability.market=synthetic",
                        "partner-observability.partners[0].key=partner-a",
                        "partner-observability.partners[0].tenant-route-id=tenant-a",
                        "partner-observability.partners[0].slot=p001",
                        "partner-observability.callbacks[0].name=decision-callback",
                        "partner-observability.callbacks[0].path=/callbacks/decision",
                        "partner-observability.callbacks[0].partner=partner-a",
                        "partner-observability.callbacks[0].capture-mode=METADATA_ONLY")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    WebFilter filter = context.getBean(PartnerCallbackWebFilter.class);
                    MockServerWebExchange exchange = MockServerWebExchange.from(
                            MockServerHttpRequest.post("/callbacks/decision")
                                    .header("Content-Type", "application/json")
                                    .body("{\"ignored\":\"synthetic\"}"));

                    filter.filter(exchange, current -> Mono.deferContextual(reactorContext -> {
                                contextObserved.set(reactorContext.hasKey(
                                        PartnerObservationContext.REACTOR_CONTEXT_KEY));
                                current.getResponse().setStatusCode(HttpStatus.ACCEPTED);
                                return current.getResponse().setComplete();
                            }))
                            .block(Duration.ofSeconds(2));

                    awaitPublished(published, 2);
                    assertThat(contextObserved).isTrue();
                    assertThat(published.stream().map(TelemetryEnvelope::body))
                            .anyMatch(CallbackRequestRecord.class::isInstance)
                            .anyMatch(CallbackResponseRecord.class::isInstance);
                    assertThat(published)
                            .allSatisfy(value -> assertThat(value.partnerContext().canonicalPartnerKey())
                                    .isEqualTo("partner-a"));
                    assertThat(published.stream()
                            .filter(value -> value.body() instanceof CallbackRequestRecord)
                            .map(value -> ((CallbackRequestRecord) value.body()).payload().payload()))
                            .allMatch(Optional::isEmpty);
                });
    }

    private void awaitPublished(List<TelemetryEnvelope<?>> published, int minimum) {
        Instant deadline = Instant.now().plusSeconds(3);
        while (published.size() < minimum && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("reactive telemetry wait interrupted", exception);
            }
        }
        assertThat(published).hasSizeGreaterThanOrEqualTo(minimum);
    }
}
