package com.samsung.sure.partner.observability.testapp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.samsung.sure.partner.observability.testapp.model.AsyncInitiationSummary;
import com.samsung.sure.partner.observability.testapp.model.SyntheticAsyncJourneySnapshot;
import com.samsung.sure.partner.observability.testapp.model.SyntheticCallbackAttempt;
import com.samsung.sure.partner.observability.testapp.model.SyntheticLifecycleStage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SyntheticAsyncLifecycleIntegrationTest {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    private TestRestTemplate controlPlane;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void returns202AcknowledgementsWithAnOptionalPartnerReferenceBridge() {
        AsyncInitiationSummary plain = start("alpha", "acknowledgement-only");
        AsyncInitiationSummary bridged = start("beta", "ack-with-partner-reference");

        assertThat(plain.acknowledgementHttpStatus()).isEqualTo(202);
        assertThat(plain.acknowledgementReceived()).isTrue();
        assertThat(plain.identifiers().partnerReferenceId()).isNull();
        assertThat(bridged.acknowledgementHttpStatus()).isEqualTo(202);
        assertThat(bridged.identifiers().partnerReferenceId())
                .startsWith("SYNTHETIC-PARTNER-REFERENCE-");
        assertThat(bridged.identifiers().externalTransactionId())
                .startsWith("SYNTHETIC-EXTERNAL-TRANSACTION-");
        assertStages(run(plain.runId()),
                SyntheticLifecycleStage.ASYNC_REQUEST_SENT,
                SyntheticLifecycleStage.ASYNC_ACK_RECEIVED);
    }

    @Test
    void supportsEachCallbackCorrelationShape() {
        SyntheticAsyncJourneySnapshot application = awaitCallbacks(
                start("alpha", "callback-with-application-id"), 1);
        SyntheticAsyncJourneySnapshot partnerReference = awaitCallbacks(
                start("alpha", "callback-with-partner-reference-only"), 1);
        SyntheticAsyncJourneySnapshot callbackReference = awaitCallbacks(
                start("alpha", "callback-with-callback-reference"), 1);

        assertThat(application.callbackAttempts().get(0).identifiers().applicationId())
                .isEqualTo("SYNTHETIC-ASYNC-APPLICATION-COLLISION-0001");
        assertThat(application.callbackAttempts().get(0).identifiers().partnerReferenceId()).isNull();
        assertThat(partnerReference.callbackAttempts().get(0).identifiers().applicationId()).isNull();
        assertThat(partnerReference.callbackAttempts().get(0).identifiers().partnerReferenceId())
                .isEqualTo(partnerReference.identifiers().partnerReferenceId());
        assertThat(callbackReference.callbackAttempts().get(0).identifiers().callbackReferenceId())
                .startsWith("SYNTHETIC-CALLBACK-REFERENCE-");
    }

    @Test
    void separatesSuccessfulAndFailedCallbackProcessingFromHttpReceipt() {
        SyntheticAsyncJourneySnapshot success = awaitCallbacks(start("alpha", "callback-success"), 1);
        SyntheticAsyncJourneySnapshot failure = awaitDeliveries(
                start("alpha", "callback-processing-failure"), 1);

        SyntheticCallbackAttempt successfulAttempt = success.callbackAttempts().get(0);
        assertThat(successfulAttempt.processingOutcome()).isEqualTo("SUCCESS");
        assertThat(successfulAttempt.responseStatus()).isEqualTo(200);
        assertThat(successfulAttempt.identifiers().applicationId()).isNotBlank();
        assertThat(successfulAttempt.identifiers().loanId()).isNotBlank();
        assertThat(successfulAttempt.identifiers().originalCorrelationId()).isNotBlank();
        assertThat(successfulAttempt.identifiers().partnerReferenceId()).isNotBlank();
        assertThat(successfulAttempt.identifiers().callbackReferenceId()).isNotBlank();
        assertThat(successfulAttempt.identifiers().externalTransactionId()).isNotBlank();
        assertStages(success,
                SyntheticLifecycleStage.CALLBACK_RECEIVED,
                SyntheticLifecycleStage.CALLBACK_PROCESSING_STARTED,
                SyntheticLifecycleStage.CALLBACK_PROCESSED,
                SyntheticLifecycleStage.CALLBACK_RESPONSE_SENT);

        SyntheticCallbackAttempt failedAttempt = failure.callbackAttempts().get(0);
        assertThat(failedAttempt.processingOutcome()).isEqualTo("BUSINESS_PROCESSING_FAILED");
        assertThat(failedAttempt.responseStatus()).isEqualTo(500);
        assertThat(failure.callbackDeliveries().get(0).httpStatus()).isEqualTo(500);
        assertThat(failure.events())
                .extracting(event -> event.stage())
                .contains(SyntheticLifecycleStage.CALLBACK_PROCESSING_FAILED);
    }

    @Test
    void modelsRetryDuplicateAndOutOfOrderDeliveriesAsSeparateAttempts() {
        SyntheticAsyncJourneySnapshot retry = awaitDeliveries(start("alpha", "callback-retry"), 2);
        SyntheticAsyncJourneySnapshot duplicate = awaitDeliveries(start("alpha", "duplicate-callback"), 2);
        SyntheticAsyncJourneySnapshot outOfOrder = awaitDeliveries(
                start("alpha", "callback-out-of-order"), 2);

        assertThat(retry.callbackAttempts())
                .extracting(SyntheticCallbackAttempt::deliveryClassification)
                .containsExactly("INITIAL", "RETRY");
        assertThat(retry.callbackDeliveries())
                .extracting(delivery -> delivery.httpStatus())
                .containsExactly(500, 200);
        assertThat(duplicate.callbackAttempts())
                .extracting(SyntheticCallbackAttempt::deliveryClassification)
                .containsExactly("INITIAL", "DUPLICATE");
        assertThat(outOfOrder.callbackAttempts())
                .extracting(SyntheticCallbackAttempt::callbackSequence)
                .containsExactly(2, 1);
        assertThat(outOfOrder.callbackAttempts())
                .extracting(SyntheticCallbackAttempt::callbackAttemptId)
                .doesNotHaveDuplicates();
    }

    @Test
    void acceptsALateCallbackAfterTheOriginalAcknowledgementTimesOut() {
        AsyncInitiationSummary initiation = start("alpha", "callback-after-outbound-timeout");
        SyntheticAsyncJourneySnapshot snapshot = awaitCallbacks(initiation, 1);

        assertThat(initiation.acknowledgementReceived()).isFalse();
        assertThat(initiation.acknowledgementHttpStatus()).isZero();
        assertStages(snapshot,
                SyntheticLifecycleStage.ASYNC_ACK_NOT_RECEIVED,
                SyntheticLifecycleStage.CALLBACK_RECEIVED,
                SyntheticLifecycleStage.CALLBACK_PROCESSED);

        SyntheticAsyncJourneySnapshot unknownReference = awaitCallbacks(
                start("beta", "unknown-partner-reference"), 1);
        assertThat(unknownReference.callbackAttempts().get(0).identifiers().partnerReferenceId())
                .isEqualTo("SYNTHETIC-UNKNOWN-PARTNER-REFERENCE-0001")
                .isNotEqualTo(unknownReference.identifiers().partnerReferenceId());
    }

    @Test
    void failsClosedForInvalidSignatureAndWrongPartnerWithoutTrustedCallbackFacts() {
        SyntheticAsyncJourneySnapshot invalidSignature = awaitDeliveries(
                start("alpha", "authentication-failure"), 1);
        SyntheticAsyncJourneySnapshot wrongPartner = awaitDeliveries(start("alpha", "wrong-partner"), 1);

        assertThat(invalidSignature.callbackDeliveries().get(0).httpStatus()).isEqualTo(401);
        assertThat(invalidSignature.callbackAttempts()).isEmpty();
        assertThat(invalidSignature.events())
                .extracting(event -> event.stage())
                .doesNotContain(SyntheticLifecycleStage.CALLBACK_RECEIVED);

        assertThat(wrongPartner.callbackDeliveries().get(0).httpStatus()).isEqualTo(403);
        assertThat(wrongPartner.callbackDeliveries().get(0).targetPartner()).isEqualTo("BETA");
        assertThat(wrongPartner.callbackAttempts()).isEmpty();
        assertThat(wrongPartner.partner()).isEqualTo("ALPHA");
    }

    @Test
    void handlesMalformedAndHostileCallbackPayloadsWithoutRetainingTheirContents() throws Exception {
        SyntheticAsyncJourneySnapshot malformed = awaitDeliveries(start("alpha", "malformed-callback"), 1);
        assertThat(malformed.callbackAttempts().get(0).payloadCategory()).isEqualTo("MALFORMED_JSON");
        assertThat(malformed.callbackAttempts().get(0).processingOutcome()).isEqualTo("PARSING_FAILED");
        assertThat(malformed.callbackDeliveries().get(0).httpStatus()).isEqualTo(400);

        SyntheticAsyncJourneySnapshot pdf = awaitCallbacks(start("alpha", "callback-pdf-base64-5-mb"), 1);
        SyntheticAsyncJourneySnapshot image = awaitCallbacks(start("alpha", "callback-image-base64"), 1);
        SyntheticAsyncJourneySnapshot pii = awaitCallbacks(start("alpha", "callback-sensitive-pii"), 1);
        SyntheticAsyncJourneySnapshot credentials = awaitCallbacks(start("alpha", "callback-credentials"), 1);

        assertThat(pdf.callbackAttempts().get(0).requestBytes()).isGreaterThan(5 * 1024 * 1024);
        assertThat(image.callbackAttempts().get(0).requestBytes()).isGreaterThan(8 * 1024 * 1024);
        assertThat(pdf.callbackAttempts().get(0).payloadCategory()).isEqualTo("PDF_BASE64");
        assertThat(image.callbackAttempts().get(0).payloadCategory()).isEqualTo("IMAGE_BASE64");
        String retainedState = objectMapper.writeValueAsString(List.of(pdf, image, pii, credentials));
        assertThat(retainedState)
                .doesNotContain("fixture.user@example.invalid")
                .doesNotContain("SYNTHETIC_CALLBACK_AUTHORIZATION_ONLY")
                .doesNotContain("SYNTHETIC_PASSWORD_ONLY");
    }

    @Test
    void preservesAcceptedBackgroundFailureAndResponseWriteFailureAsDistinctFacts() {
        SyntheticAsyncJourneySnapshot background = awaitSnapshot(
                start("alpha", "accepted-then-downstream-failure"),
                snapshot -> !snapshot.callbackAttempts().isEmpty()
                        && "DOWNSTREAM_FAILED".equals(snapshot.callbackAttempts().get(0).processingOutcome()));
        List<SyntheticLifecycleStage> backgroundStages = background.events().stream()
                .map(event -> event.stage())
                .toList();
        assertThat(background.callbackAttempts().get(0).responseStatus()).isEqualTo(202);
        assertThat(backgroundStages.indexOf(SyntheticLifecycleStage.CALLBACK_RESPONSE_SENT))
                .isLessThan(backgroundStages.indexOf(SyntheticLifecycleStage.CALLBACK_PROCESSING_STARTED));

        SyntheticAsyncJourneySnapshot writeFailure = awaitSnapshot(
                start("alpha", "response-transmission-failure"),
                snapshot -> !snapshot.callbackAttempts().isEmpty()
                        && "WRITE_FAILED".equals(snapshot.callbackAttempts().get(0).responseTransportOutcome()));
        assertThat(writeFailure.callbackAttempts().get(0).processingOutcome()).isEqualTo("SUCCESS");
        assertThat(writeFailure.events())
                .extracting(event -> event.stage())
                .contains(SyntheticLifecycleStage.CALLBACK_PROCESSED)
                .contains(SyntheticLifecycleStage.CALLBACK_RESPONSE_WRITE_FAILED);
    }

    @Test
    void scopesTheSameCallbackReferenceToTwoSyntheticPartners() {
        SyntheticAsyncJourneySnapshot alpha = awaitCallbacks(
                start("alpha", "cross-partner-callback-reference"), 1);
        SyntheticAsyncJourneySnapshot beta = awaitCallbacks(
                start("beta", "cross-partner-callback-reference"), 1);

        assertThat(alpha.partner()).isEqualTo("ALPHA");
        assertThat(beta.partner()).isEqualTo("BETA");
        assertThat(alpha.callbackAttempts().get(0).identifiers().callbackReferenceId())
                .isEqualTo(beta.callbackAttempts().get(0).identifiers().callbackReferenceId())
                .isEqualTo("SYNTHETIC-CALLBACK-REFERENCE-COLLISION-0001");
        assertThat(alpha.callbackAttempts().get(0).deliveryClassification()).isEqualTo("INITIAL");
        assertThat(beta.callbackAttempts().get(0).deliveryClassification()).isEqualTo("INITIAL");
    }

    @Test
    void supportsBoundedHighConcurrencyAndMultipleCallbacksPerInitiation() {
        SyntheticAsyncJourneySnapshot concurrent = awaitDeliveries(
                start("alpha", "high-concurrency-callbacks"), 32);
        SyntheticAsyncJourneySnapshot multiple = awaitDeliveries(start("beta", "multiple-callbacks"), 3);

        assertThat(concurrent.callbackAttempts()).hasSize(32);
        assertThat(concurrent.callbackAttempts())
                .extracting(attempt -> attempt.identifiers().callbackReferenceId())
                .doesNotHaveDuplicates();
        assertThat(concurrent.callbackDeliveries())
                .allSatisfy(delivery -> {
                    assertThat(delivery.targetPartner()).isEqualTo("ALPHA");
                    assertThat(delivery.httpStatus()).isEqualTo(200);
                });
        assertThat(multiple.callbackAttempts()).hasSize(3);
        assertThat(multiple.callbackAttempts())
                .extracting(SyntheticCallbackAttempt::callbackSequence)
                .containsExactly(1, 2, 3);
    }

    private AsyncInitiationSummary start(String partner, String scenario) {
        ResponseEntity<AsyncInitiationSummary> response = controlPlane.postForEntity(
                "/fixture/async/" + partner + "/" + scenario, null, AsyncInitiationSummary.class);
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private SyntheticAsyncJourneySnapshot awaitCallbacks(AsyncInitiationSummary initiation, int count) {
        return awaitSnapshot(initiation, snapshot -> snapshot.callbackAttempts().size() >= count);
    }

    private SyntheticAsyncJourneySnapshot awaitDeliveries(AsyncInitiationSummary initiation, int count) {
        return awaitSnapshot(initiation, snapshot -> snapshot.callbackDeliveries().size() >= count);
    }

    private SyntheticAsyncJourneySnapshot awaitSnapshot(
            AsyncInitiationSummary initiation, Predicate<SyntheticAsyncJourneySnapshot> condition) {
        Instant deadline = Instant.now().plus(AWAIT_TIMEOUT);
        SyntheticAsyncJourneySnapshot snapshot;
        do {
            snapshot = run(initiation.runId());
            if (condition.test(snapshot)) {
                return snapshot;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("SYNTHETIC_AWAIT_INTERRUPTED", exception);
            }
        } while (Instant.now().isBefore(deadline));
        throw new AssertionError("SYNTHETIC_ASYNC_SCENARIO_DID_NOT_COMPLETE");
    }

    private SyntheticAsyncJourneySnapshot run(String runId) {
        ResponseEntity<SyntheticAsyncJourneySnapshot> response = controlPlane.getForEntity(
                "/fixture/async/runs/" + runId, SyntheticAsyncJourneySnapshot.class);
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private void assertStages(
            SyntheticAsyncJourneySnapshot snapshot, SyntheticLifecycleStage... expectedInOrder) {
        assertThat(snapshot.events())
                .extracting(event -> event.stage())
                .containsSubsequence(expectedInOrder);
    }
}
