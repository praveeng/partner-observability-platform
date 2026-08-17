package com.partner.observability.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.partner.observability.core.TestFixtures;
import com.partner.observability.core.payload.FailClosedPayloadSanitizer;
import com.partner.observability.core.payload.PayloadInput;
import com.partner.observability.core.payload.PayloadSchema;
import com.partner.observability.core.payload.PayloadStatus;
import com.partner.observability.core.payload.SanitizationResult;
import com.partner.observability.core.policy.PayloadCaptureMode;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoreTelemetryModelTest {

    private final SanitizationResult omitted = SanitizationResult.omitted(PayloadStatus.NOT_REQUESTED);

    @Test
    void modelsAllSchemaTwoInteractionFactsWithoutFrameworkTypes() {
        assertEquals(TelemetryRecordType.OUTBOUND_API_REQUEST, request().recordType());
        assertEquals(TelemetryRecordType.OUTBOUND_API_RESPONSE, response().recordType());
        assertEquals(TelemetryRecordType.ASYNC_ACKNOWLEDGEMENT, acknowledgement().recordType());
        assertEquals(TelemetryRecordType.CALLBACK_REQUEST, callbackRequest().recordType());
        assertEquals(TelemetryRecordType.CALLBACK_RESPONSE, callbackResponse().recordType());
        assertEquals(TelemetryRecordType.CALLBACK_PROCESSING_EVENT, processing().recordType());
        assertEquals(TelemetryRecordType.PARTNER_BUSINESS_EVENT,
                TestFixtures.submission(TestFixtures.context("partner-a", "uk-dev-partner-a", "p001"), 200)
                        .envelope().body().recordType());
    }

    @Test
    void callbackReceiptAndProcessingRequireDistinctRecordsAndAttemptIdentity() {
        UUID attempt = UUID.randomUUID();
        UUID interaction = UUID.randomUUID();
        CorrelationIdentifiers identifiers = identifiers();
        InteractionContext received = new InteractionContext(
                InteractionKind.CALLBACK, Direction.INBOUND_FROM_PARTNER, interaction, 0,
                Optional.of(attempt), "async-profile", identifiers, Optional.of(TimelineStage.CALLBACK_RECEIVED));
        InteractionContext processed = received.withSequenceAndStage(1, TimelineStage.CALLBACK_PROCESSED);

        assertEquals(attempt, received.callbackAttemptId().orElseThrow());
        assertEquals(received.interactionId(), processed.interactionId());
        assertEquals(TimelineStage.CALLBACK_RECEIVED, received.timelineStage().orElseThrow());
        assertEquals(TimelineStage.CALLBACK_PROCESSED, processed.timelineStage().orElseThrow());
    }

    @Test
    void envelopeRequiresSchemaInteractionAndCaptureCoherence() {
        var context = TestFixtures.context("partner-a", "uk-dev-partner-a", "p001");
        InteractionContext synchronous = new InteractionContext(
                InteractionKind.SYNC_OUTBOUND, Direction.OUTBOUND_TO_PARTNER, UUID.randomUUID(), 1,
                Optional.empty(), "sync-profile", identifiers(), Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> new CaptureDecision(
                PayloadCaptureMode.METADATA_ONLY, PayloadCaptureMode.FULL_SANITIZED, "bad"));
        assertThrows(IllegalArgumentException.class, () -> new CorrelationIdentifiers(
                Optional.of("secret=abc"), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> envelope(
                context, synchronous, PayloadCaptureMode.NO_PAYLOAD, PayloadStatus.NOT_REQUESTED, response()));
        assertThrows(IllegalArgumentException.class, () -> envelope(
                context, synchronous, PayloadCaptureMode.METADATA_ONLY, PayloadStatus.NOT_REQUESTED, callbackRequest()));

        SanitizationResult captured = new FailClosedPayloadSanitizer().sanitize(
                PayloadInput.of(Map.of("status", "SAFE")),
                PayloadSchema.builder().allow("status").build(), PayloadCaptureMode.FULL_SANITIZED);
        OutboundApiResponseRecord body = new OutboundApiResponseRecord(
                "submit", OptionalInt.of(200), StatusClass.TWO_XX, Outcome.SUCCESS, 1,
                Optional.empty(), Optional.of(TransportSecurity.TLS), Optional.empty(),
                Optional.of("application/json"), OptionalLong.empty(), omitted, captured);
        assertThrows(IllegalArgumentException.class, () -> envelope(
                context, synchronous, PayloadCaptureMode.METADATA_ONLY, PayloadStatus.CAPTURED, body));

        assertThrows(IllegalArgumentException.class, () -> new OutboundApiResponseRecord(
                "submit", OptionalInt.empty(), StatusClass.IO_ERROR, Outcome.TECHNICAL_FAILURE, 1,
                Optional.of("tls_handshake"), Optional.empty(), Optional.of(TransportFailureClass.TLS_HANDSHAKE),
                Optional.empty(), OptionalLong.empty(), omitted, omitted));
    }

    @Test
    void schemaOneBodiesRemainAcceptedOnlyInSchemaOneMigrationEnvelope() {
        PartnerEvent legacy = new PartnerEvent(
                "submitted", "APPLICATION", Outcome.SUCCESS, Optional.empty(), Optional.empty(), Optional.empty(),
                OptionalInt.empty(), Optional.empty(), Optional.empty(), Optional.empty(), omitted,
                TransactionIdentifiers.empty());
        var context = TestFixtures.context("partner-a", "uk-dev-partner-a", "p001");
        assertThrows(IllegalArgumentException.class, () -> new TelemetryEnvelope<>(
                TelemetryEnvelope.CURRENT_SCHEMA_VERSION, UUID.randomUUID(), Instant.now(), Instant.now(),
                new ServiceIdentity("service", "1.0"), context, Direction.OUTBOUND_TO_PARTNER,
                UUID.randomUUID(), 0,
                new CaptureDecision(PayloadCaptureMode.METADATA_ONLY, PayloadCaptureMode.METADATA_ONLY, "v1"),
                PayloadStatus.NOT_REQUESTED, Severity.INFO, legacy));
        assertEquals(TelemetryEnvelope.LEGACY_SCHEMA_VERSION, new TelemetryEnvelope<>(
                TelemetryEnvelope.LEGACY_SCHEMA_VERSION, UUID.randomUUID(), Instant.now(), Instant.now(),
                new ServiceIdentity("service", "1.0"), context, Direction.OUTBOUND_TO_PARTNER,
                UUID.randomUUID(), 0,
                new CaptureDecision(PayloadCaptureMode.METADATA_ONLY, PayloadCaptureMode.METADATA_ONLY, "v1"),
                PayloadStatus.NOT_REQUESTED, Severity.INFO, legacy).schemaVersion());
    }

    private TelemetryEnvelope<?> envelope(
            com.partner.observability.core.context.PartnerContext context,
            InteractionContext interaction,
            PayloadCaptureMode mode,
            PayloadStatus status,
            TelemetryRecord body) {
        return new TelemetryEnvelope<>(
                TelemetryEnvelope.CURRENT_SCHEMA_VERSION, UUID.randomUUID(), Instant.now(), Instant.now(),
                new ServiceIdentity("service", "1.0"), context, interaction,
                new CaptureDecision(mode, mode, "v2"), status, Severity.INFO, Outcome.SUCCESS, body);
    }

    private CorrelationIdentifiers identifiers() {
        return new CorrelationIdentifiers(
                Optional.of("APP-1"), Optional.of("LOAN-1"), Optional.of("CORR-1"),
                Optional.of("PREF-1"), Optional.of("EXT-1"), Optional.of("CALLBACK-1"), Optional.of("REQ-1"));
    }

    private OutboundApiRequestRecord request() {
        return new OutboundApiRequestRecord(
                "submit", "/v1/applications/{id}", ExchangeMode.SYNC, PartnerHttpMethod.POST, 1,
                Optional.of("application/json"), OptionalLong.of(128), omitted, omitted, omitted,
                TransportState.DELEGATED, Optional.of(TransportSecurity.TLS));
    }

    private OutboundApiResponseRecord response() {
        return new OutboundApiResponseRecord(
                "submit", OptionalInt.of(200), StatusClass.TWO_XX, Outcome.SUCCESS, 12,
                Optional.empty(), Optional.of(TransportSecurity.TLS), Optional.empty(),
                Optional.of("application/json"), OptionalLong.of(64), omitted, omitted);
    }

    private AsyncAcknowledgementRecord acknowledgement() {
        return new AsyncAcknowledgementRecord(
                "submit_async", OptionalInt.of(202), StatusClass.TWO_XX,
                AcknowledgementOutcome.ACCEPTED, Outcome.SUCCESS, 12,
                ProcessingDisposition.PARTNER_PROCESSING_EXPECTED, Optional.empty(),
                Optional.of(TransportSecurity.TLS), Optional.empty(), Optional.of("application/json"),
                OptionalLong.of(64), omitted, omitted);
    }

    private CallbackRequestRecord callbackRequest() {
        return new CallbackRequestRecord(
                "decision_callback", "/callbacks/decision", PartnerHttpMethod.POST,
                DeliveryClassification.INITIAL, Optional.of("application/json"), OptionalLong.of(64),
                omitted, omitted, ParsingStatus.PARSED, Instant.now(), Optional.of(TransportSecurity.ALB_TLS));
    }

    private CallbackResponseRecord callbackResponse() {
        return new CallbackResponseRecord(
                "decision_callback", OptionalInt.of(200), StatusClass.TWO_XX, Outcome.SUCCESS, 5,
                TransportOutcome.WRITE_COMPLETED, Optional.empty(), Optional.of("application/json"),
                OptionalLong.of(32), omitted, omitted);
    }

    private CallbackProcessingEventRecord processing() {
        return new CallbackProcessingEventRecord(
                "decision_callback", ProcessingMode.INLINE, ProcessingPhase.BUSINESS_PROCESSING,
                Outcome.SUCCESS, Optional.empty(), OptionalLong.of(3), Optional.of(false), omitted);
    }
}
