package com.samsung.sure.partner.observability.testapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samsung.sure.partner.observability.core.dispatch.DropReason;
import com.samsung.sure.partner.observability.core.health.TelemetryHealth;
import com.samsung.sure.partner.observability.core.model.AsyncAcknowledgementRecord;
import com.samsung.sure.partner.observability.core.model.CallbackProcessingEventRecord;
import com.samsung.sure.partner.observability.core.model.CallbackRequestRecord;
import com.samsung.sure.partner.observability.core.model.CallbackResponseRecord;
import com.samsung.sure.partner.observability.core.model.DeliveryClassification;
import com.samsung.sure.partner.observability.core.model.OutboundApiRequestRecord;
import com.samsung.sure.partner.observability.core.model.OutboundApiResponseRecord;
import com.samsung.sure.partner.observability.core.model.Outcome;
import com.samsung.sure.partner.observability.core.model.StatusClass;
import com.samsung.sure.partner.observability.core.model.TelemetryEnvelope;
import com.samsung.sure.partner.observability.core.model.TimelineStage;
import com.samsung.sure.partner.observability.core.payload.PayloadStatus;
import com.samsung.sure.partner.observability.core.payload.SanitizationResult;
import com.samsung.sure.partner.observability.testapp.client.OkHttpFixtureClient;
import com.samsung.sure.partner.observability.testapp.client.RestTemplateFixtureClient;
import com.samsung.sure.partner.observability.testapp.client.WebClientFixtureClient;
import com.samsung.sure.partner.observability.testapp.model.AsyncInitiationSummary;
import com.samsung.sure.partner.observability.testapp.model.SyntheticAsyncJourneySnapshot;
import com.samsung.sure.partner.observability.testapp.model.SyntheticPartner;
import com.samsung.sure.partner.observability.testapp.model.SyntheticScenario;
import com.samsung.sure.partner.observability.testapp.telemetry.SyntheticTelemetryCollector;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PartnerObservabilityStarterIntegrationTest {
    private static final Duration AWAIT = Duration.ofSeconds(20);

    @Autowired SyntheticTelemetryCollector telemetry;
    @Autowired TelemetryHealth health;
    @Autowired RestTemplateFixtureClient restTemplate;
    @Autowired WebClientFixtureClient webClient;
    @Autowired OkHttpFixtureClient okHttp;
    @Autowired TestRestTemplate controlPlane;
    @Autowired MeterRegistry meterRegistry;

    @BeforeEach
    void clearTelemetry() {
        telemetry.releasePublishing();
        telemetry.clear();
    }

    @AfterEach
    void restorePublisher() {
        telemetry.failPublishing(false);
        telemetry.releasePublishing();
    }

    @Test
    void starterRegistersTheBoundedPartnerMetricManifestWithTheApplicationRegistry() {
        assertThat(meterRegistry.find("partner_observability_http_interactions_total").counters())
                .isNotEmpty();
        assertThat(meterRegistry.find("partner_observability_callback_deliveries_total").counters())
                .isNotEmpty();
    }

    @Test
    void capturesAllThreeOutboundClientsWithoutChangingTheirResults() throws Exception {
        Instant startedAt = Instant.now();
        String applicationId = "SYNTHETIC-STARTER-CLIENTS-0001";
        assertThat(restTemplate.exchange(SyntheticScenario.NORMAL_JSON, SyntheticPartner.ALPHA, applicationId).httpStatus())
                .isEqualTo(200);
        assertThat(webClient.exchange(SyntheticScenario.NORMAL_JSON, SyntheticPartner.ALPHA, applicationId)
                .block(Duration.ofSeconds(5)).httpStatus()).isEqualTo(200);
        assertThat(okHttp.exchange(SyntheticScenario.NORMAL_JSON, SyntheticPartner.ALPHA, applicationId).httpStatus())
                .isEqualTo(200);

        List<TelemetryEnvelope<?>> records = awaitRecords(values -> count(recordsSince(values, startedAt), OutboundApiRequestRecord.class) >= 3
                && count(recordsSince(values, startedAt), OutboundApiResponseRecord.class) >= 3);
        records = recordsSince(records, startedAt);
        assertThat(records.stream()
                .filter(value -> value.body() instanceof OutboundApiRequestRecord)
                .allMatch(value -> value.partnerContext().canonicalPartnerKey().equals("partner-alpha-fixture")))
                .isTrue();
        assertThat(records.stream()
                .filter(value -> value.body() instanceof OutboundApiRequestRecord request
                        && request.payload().status() == PayloadStatus.CAPTURED)
                .count()).isGreaterThanOrEqualTo(1);
        assertThat(records.stream()
                .filter(value -> value.body() instanceof OutboundApiRequestRecord request
                        && request.payload().status() == PayloadStatus.UNSUPPORTED_INTEGRATION)
                .count()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void capturesTrustedOutboundRetryAttemptInformation() {
        String applicationId = "SYNTHETIC-STARTER-RETRY-0001";
        assertThat(restTemplate.exchange(SyntheticScenario.RETRY, SyntheticPartner.ALPHA, applicationId).attempts())
                .isEqualTo(2);
        List<TelemetryEnvelope<?>> records = awaitRecords(values -> values.stream()
                .filter(value -> value.body() instanceof OutboundApiRequestRecord)
                .filter(value -> value.interactionContext().identifiers().applicationId()
                        .filter(applicationId::equals).isPresent())
                .count() >= 2);
        assertThat(records.stream()
                .filter(value -> value.body() instanceof OutboundApiRequestRecord)
                .filter(value -> value.interactionContext().identifiers().applicationId()
                        .filter(applicationId::equals).isPresent())
                .map(value -> ((OutboundApiRequestRecord) value.body()).attempt()))
                .containsExactly(1, 2);
    }

    @Test
    void classifiesOutboundHttpErrorsTimeoutsAndConnectionFailuresWithoutChangingExceptions() {
        Instant startedAt = Instant.now();
        String rejectedId = "SYNTHETIC-STARTER-4XX-0001";
        String unavailableId = "SYNTHETIC-STARTER-5XX-0001";
        String timeoutId = "SYNTHETIC-STARTER-TIMEOUT-0001";
        String connectionId = "SYNTHETIC-STARTER-CONNECTION-0001";

        assertThat(restTemplate.exchange(
                SyntheticScenario.PARTNER_4XX, SyntheticPartner.ALPHA, rejectedId).httpStatus())
                .isEqualTo(422);
        assertThat(restTemplate.exchange(
                SyntheticScenario.PARTNER_5XX, SyntheticPartner.ALPHA, unavailableId).httpStatus())
                .isEqualTo(503);
        assertThatThrownBy(() -> restTemplate.exchange(
                SyntheticScenario.TIMEOUT, SyntheticPartner.ALPHA, timeoutId))
                .isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
        assertThatThrownBy(() -> restTemplate.exchange(
                SyntheticScenario.CONNECTION_FAILURE, SyntheticPartner.ALPHA, connectionId))
                .isInstanceOf(org.springframework.web.client.ResourceAccessException.class);

        awaitRecords(values -> count(recordsSince(values, startedAt), OutboundApiResponseRecord.class) >= 2);
        awaitCondition(() -> health.snapshot().highQueueEvents() == 0
                && health.snapshot().normalQueueEvents() == 0);
        List<TelemetryEnvelope<?>> records = recordsSince(telemetry.snapshot(), startedAt);
        assertThat(records.stream().filter(value -> value.body() instanceof OutboundApiResponseRecord))
                .as("one terminal response record per attempted exchange")
                .hasSize(4);
        assertThat(terminalFor(records, rejectedId).orElseThrow())
                .satisfies(record -> {
                    assertThat(record.statusClass()).isEqualTo(StatusClass.FOUR_XX);
                    assertThat(record.outcome()).isEqualTo(Outcome.BUSINESS_REJECTED);
                    assertThat(record.httpStatus()).hasValue(422);
                });
        assertThat(terminalFor(records, unavailableId).orElseThrow())
                .satisfies(record -> {
                    assertThat(record.statusClass()).isEqualTo(StatusClass.FIVE_XX);
                    assertThat(record.outcome()).isEqualTo(Outcome.TECHNICAL_FAILURE);
                    assertThat(record.httpStatus()).hasValue(503);
                });
        assertThat(terminalFor(records, timeoutId).orElseThrow())
                .satisfies(record -> {
                    assertThat(record.statusClass()).isEqualTo(StatusClass.IO_ERROR);
                    assertThat(record.outcome()).isEqualTo(Outcome.TECHNICAL_FAILURE);
                    assertThat(record.errorCode()).contains("timeout");
                });
        assertThat(terminalFor(records, connectionId).orElseThrow())
                .satisfies(record -> {
                    assertThat(record.statusClass()).isEqualTo(StatusClass.IO_ERROR);
                    assertThat(record.outcome()).isEqualTo(Outcome.TECHNICAL_FAILURE);
                    assertThat(record.errorCode()).contains("transport_failure");
                });
        assertThat(records.stream()
                .filter(value -> value.body() instanceof OutboundApiResponseRecord)
                .filter(value -> value.interactionContext().identifiers().applicationId()
                        .filter(id -> List.of(rejectedId, unavailableId, timeoutId, connectionId).contains(id))
                        .isPresent())
                .allMatch(value -> value.partnerContext().canonicalPartnerKey().equals("partner-alpha-fixture")))
                .isTrue();
        assertThat(recordsSince(records, startedAt)).isNotEmpty();
    }

    @Test
    void capturesAcknowledgementCallbackCorrelationAndIndependentDuplicateAttempts() {
        AsyncInitiationSummary initiation = start("alpha", "duplicate-callback");
        SyntheticAsyncJourneySnapshot journey = awaitJourney(initiation.runId(), value -> value.callbackAttempts().size() >= 2);
        String partnerReference = journey.identifiers().partnerReferenceId();

        List<TelemetryEnvelope<?>> records = awaitRecords(values -> values.stream()
                .filter(value -> value.body() instanceof CallbackRequestRecord)
                .filter(value -> value.interactionContext().identifiers().partnerReferenceId()
                        .filter(partnerReference::equals).isPresent())
                .count() >= 2);
        List<TelemetryEnvelope<?>> acknowledgements = records.stream()
                .filter(value -> value.body() instanceof AsyncAcknowledgementRecord)
                .toList();
        assertThat(acknowledgements.stream().anyMatch(value -> value.interactionContext().identifiers()
                        .partnerReferenceId().filter(partnerReference::equals).isPresent()))
                .as("async acknowledgement identifiers: %s", acknowledgements.stream()
                        .map(value -> value.interactionContext().identifiers()).toList())
                .isTrue();
        List<TelemetryEnvelope<?>> callbacks = records.stream()
                .filter(value -> value.body() instanceof CallbackRequestRecord)
                .filter(value -> value.interactionContext().identifiers().partnerReferenceId()
                        .filter(partnerReference::equals).isPresent())
                .toList();
        assertThat(callbacks).hasSize(2);
        assertThat(callbacks.stream().map(value -> value.interactionContext().callbackAttemptId().orElseThrow()))
                .doesNotHaveDuplicates();
        assertThat(callbacks.stream().map(value -> ((CallbackRequestRecord) value.body()).deliveryClassification()))
                .containsExactly(DeliveryClassification.INITIAL, DeliveryClassification.DUPLICATE);
        assertThat(records.stream().anyMatch(value -> value.body() instanceof CallbackResponseRecord)).isTrue();
    }

    @Test
    void observesCallbackProcessingFailureAndRetryAsSeparateLifecycleFacts() {
        AsyncInitiationSummary failed = start("alpha", "callback-processing-failure");
        awaitJourney(failed.runId(), value -> !value.callbackDeliveries().isEmpty());
        AsyncInitiationSummary retry = start("beta", "callback-retry");
        SyntheticAsyncJourneySnapshot retryJourney = awaitJourney(
                retry.runId(), value -> value.callbackAttempts().size() >= 2);

        List<TelemetryEnvelope<?>> records = awaitRecords(values -> values.stream()
                .anyMatch(value -> value.interactionContext().timelineStage()
                        .filter(TimelineStage.CALLBACK_PROCESSING_FAILED::equals).isPresent())
                && values.stream().anyMatch(value -> value.body() instanceof CallbackRequestRecord request
                        && request.deliveryClassification() == DeliveryClassification.RETRY));
        assertThat(records.stream().filter(value -> value.body() instanceof CallbackProcessingEventRecord)
                .map(value -> value.interactionContext().timelineStage().orElse(null)))
                .contains(TimelineStage.CALLBACK_PROCESSING_FAILED, TimelineStage.CALLBACK_PROCESSING_STARTED);
        assertThat(retryJourney.callbackAttempts()).hasSize(2);
    }

    @Test
    void omitsLargeCallbackDocumentsBeforeQueueAdmissionAndKeepsPartnerIsolation() {
        String outboundDocumentId = "SYNTHETIC-STARTER-PDF-0001";
        assertThat(restTemplate.exchange(
                        SyntheticScenario.PDF_BASE64_5_MB, SyntheticPartner.ALPHA, outboundDocumentId)
                .responseBody().length()).isGreaterThan(5 * 1024 * 1024);
        AsyncInitiationSummary pdf = start("alpha", "callback-pdf-base64-5-mb");
        awaitJourney(pdf.runId(), value -> !value.callbackAttempts().isEmpty());
        AsyncInitiationSummary wrongPartner = start("alpha", "wrong-partner");
        awaitJourney(wrongPartner.runId(), value -> !value.callbackDeliveries().isEmpty());
        AsyncInitiationSummary unknown = start("beta", "unknown-partner-reference");
        SyntheticAsyncJourneySnapshot unknownJourney = awaitJourney(
                unknown.runId(), value -> !value.callbackAttempts().isEmpty());
        String unknownReference = unknownJourney.callbackAttempts().get(0).identifiers().partnerReferenceId();

        List<TelemetryEnvelope<?>> records = awaitRecords(values -> values.stream()
                .anyMatch(value -> value.body() instanceof CallbackRequestRecord request
                        && request.payload().status() == PayloadStatus.OVERSIZE)
                && values.stream().anyMatch(value -> value.body() instanceof OutboundApiResponseRecord response
                        && response.payload().status() == PayloadStatus.OVERSIZE
                        && value.interactionContext().identifiers().applicationId()
                                .filter(outboundDocumentId::equals).isPresent())
                && values.stream().anyMatch(value -> value.body() instanceof CallbackRequestRecord
                        && value.partnerContext().canonicalPartnerKey().equals("partner-beta-fixture")
                        && value.interactionContext().identifiers().partnerReferenceId()
                                .filter(unknownReference::equals).isPresent()));
        assertThat(records.stream()
                .filter(value -> value.body() instanceof CallbackRequestRecord request
                        && request.payload().status() == PayloadStatus.OVERSIZE)
                .allMatch(value -> ((CallbackRequestRecord) value.body()).payload().payload().isEmpty()))
                .isTrue();
        assertThat(records.stream()
                .filter(value -> value.body() instanceof OutboundApiResponseRecord response
                        && response.payload().status() == PayloadStatus.OVERSIZE)
                .allMatch(value -> ((OutboundApiResponseRecord) value.body()).payload().payload().isEmpty()))
                .isTrue();
        assertThat(safePayloadText(records))
                .doesNotContain("JVBER")
                .doesNotContain("SYNTHETIC_PASSWORD_ONLY")
                .doesNotContain("Bearer ");
        assertThat(records.stream()
                .filter(value -> value.body() instanceof CallbackRequestRecord)
                .noneMatch(value -> value.partnerContext().canonicalPartnerKey().equals("partner-beta-fixture")
                        && value.interactionContext().identifiers().originalCorrelationId()
                                .filter(id -> id.equals(wrongPartner.identifiers().originalCorrelationId())).isPresent()))
                .isTrue();
    }

    @Test
    void publisherFailureAndQueueSaturationNeverFailBusinessCalls() throws Exception {
        long failuresBefore = health.snapshot().publisherFailures();
        telemetry.failPublishing(true);
        assertThat(restTemplate.exchange(SyntheticScenario.SUCCESS, SyntheticPartner.ALPHA).httpStatus())
                .isEqualTo(200);
        awaitCondition(() -> health.snapshot().publisherFailures() > failuresBefore);
        telemetry.failPublishing(false);

        long dropsBefore = health.snapshot().drops(DropReason.QUEUE_EVENT_CAPACITY);
        telemetry.pausePublishing();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                12, 12, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(600),
                new ThreadPoolExecutor.AbortPolicy());
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int index = 0; index < 540; index++) {
                int request = index;
                results.add(executor.submit(() -> restTemplate.exchange(
                        SyntheticScenario.SUCCESS,
                        request % 2 == 0 ? SyntheticPartner.ALPHA : SyntheticPartner.BETA,
                        "SYNTHETIC-SATURATION-" + request).httpStatus()));
            }
            for (Future<Integer> result : results) {
                assertThat(result.get(20, TimeUnit.SECONDS)).isEqualTo(200);
            }
            assertThat(health.snapshot().drops(DropReason.QUEUE_EVENT_CAPACITY)).isGreaterThan(dropsBefore);
        } finally {
            telemetry.releasePublishing();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private AsyncInitiationSummary start(String partner, String scenario) {
        ResponseEntity<AsyncInitiationSummary> response = controlPlane.postForEntity(
                "/fixture/async/" + partner + "/" + scenario, null, AsyncInitiationSummary.class);
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        return response.getBody();
    }

    private SyntheticAsyncJourneySnapshot awaitJourney(
            String runId, Predicate<SyntheticAsyncJourneySnapshot> condition) {
        Instant deadline = Instant.now().plus(AWAIT);
        while (Instant.now().isBefore(deadline)) {
            SyntheticAsyncJourneySnapshot value = controlPlane.getForObject(
                    "/fixture/async/runs/" + runId, SyntheticAsyncJourneySnapshot.class);
            if (value != null && condition.test(value)) return value;
            sleep();
        }
        throw new AssertionError("SYNTHETIC_JOURNEY_TIMEOUT");
    }

    private List<TelemetryEnvelope<?>> awaitRecords(Predicate<List<TelemetryEnvelope<?>>> condition) {
        Instant deadline = Instant.now().plus(AWAIT);
        while (Instant.now().isBefore(deadline)) {
            List<TelemetryEnvelope<?>> values = telemetry.snapshot();
            if (condition.test(values)) return values;
            sleep();
        }
        throw new AssertionError("SYNTHETIC_TELEMETRY_TIMEOUT");
    }

    private long count(List<TelemetryEnvelope<?>> values, Class<?> bodyType) {
        return values.stream().filter(value -> bodyType.isInstance(value.body())).count();
    }

    private Optional<OutboundApiResponseRecord> terminalFor(
            List<TelemetryEnvelope<?>> values, String applicationId) {
        return values.stream()
                .filter(value -> value.body() instanceof OutboundApiResponseRecord)
                .filter(value -> value.interactionContext().identifiers().applicationId()
                        .filter(applicationId::equals).isPresent())
                .map(value -> (OutboundApiResponseRecord) value.body())
                .findFirst();
    }

    private List<TelemetryEnvelope<?>> recordsSince(List<TelemetryEnvelope<?>> values, Instant startedAt) {
        return values.stream().filter(value -> !value.occurredAt().isBefore(startedAt)).toList();
    }

    private String safePayloadText(List<TelemetryEnvelope<?>> records) {
        StringBuilder result = new StringBuilder();
        for (TelemetryEnvelope<?> envelope : records) {
            SanitizationResult payload = payload(envelope);
            payload.payload().ifPresent(value -> result.append(value.value().toJavaValue()));
        }
        return result.toString();
    }

    private SanitizationResult payload(TelemetryEnvelope<?> envelope) {
        if (envelope.body() instanceof OutboundApiRequestRecord value) return value.payload();
        if (envelope.body() instanceof OutboundApiResponseRecord value) return value.payload();
        if (envelope.body() instanceof AsyncAcknowledgementRecord value) return value.payload();
        if (envelope.body() instanceof CallbackRequestRecord value) return value.payload();
        if (envelope.body() instanceof CallbackResponseRecord value) return value.payload();
        return SanitizationResult.omitted(PayloadStatus.NOT_REQUESTED);
    }

    private void sleep() {
        try {
            Thread.sleep(25);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("SYNTHETIC_AWAIT_INTERRUPTED", exception);
        }
    }

    private void awaitCondition(java.util.function.BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(AWAIT);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) return;
            sleep();
        }
        throw new AssertionError("SYNTHETIC_CONDITION_TIMEOUT");
    }
}
