package com.partner.observability.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.partner.observability.core.TestFixtures;
import com.partner.observability.core.payload.PayloadStatus;
import com.partner.observability.core.payload.SanitizationResult;
import com.partner.observability.core.policy.PayloadCaptureMode;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoreTelemetryModelTest {

    @Test
    void modelsRequestResponseAndEventWithoutFrameworkTypes() {
        SanitizationResult omitted = SanitizationResult.omitted(PayloadStatus.NOT_REQUESTED);
        TransactionIdentifiers ids = new TransactionIdentifiers(
                Optional.of("APP-1"), Optional.of("LOAN-1"), Optional.of("CORR-1"), Optional.of("REQ-1"), Optional.of("PREF-1"));

        PartnerApiRequest request = new PartnerApiRequest(
                "submit", "/v1/applications/{id}", PartnerHttpMethod.POST, 1, Optional.of("application/json"),
                OptionalLong.of(128), omitted, omitted, omitted, ids);
        PartnerApiResponse response = new PartnerApiResponse(
                "submit", OptionalInt.of(200), StatusClass.TWO_XX, Outcome.SUCCESS, 12, Optional.empty(),
                Optional.of("application/json"), OptionalLong.of(64), omitted, omitted, ids);
        PartnerEvent event = new PartnerEvent(
                "submitted", "APPLICATION", Outcome.SUCCESS, Optional.empty(), Optional.empty(), Optional.empty(),
                OptionalInt.empty(), Optional.empty(), Optional.empty(), Optional.empty(), omitted, ids);

        assertEquals(TelemetryRecordType.API_REQUEST, request.recordType());
        assertEquals(TelemetryRecordType.API_RESPONSE, response.recordType());
        assertEquals(TelemetryRecordType.PARTNER_EVENT, event.recordType());
    }

    @Test
    void rejectedSanitizationCannotEnterARecord() {
        SanitizationResult rejected = SanitizationResult.rejected(PayloadStatus.MALFORMED);
        assertThrows(IllegalArgumentException.class, () -> new PartnerApiRequest(
                "submit", "/v1/submit", PartnerHttpMethod.POST, 1, Optional.empty(), OptionalLong.empty(),
                SanitizationResult.omitted(PayloadStatus.NOT_REQUESTED),
                SanitizationResult.omitted(PayloadStatus.NOT_REQUESTED), rejected, TransactionIdentifiers.empty()));
    }

    @Test
    void envelopeRequiresCoherentSafeMetadata() {
        TelemetrySubmissionView fixture = envelope();
        assertEquals("partner-a", fixture.envelope.partnerContext().canonicalPartnerKey());
        assertThrows(IllegalArgumentException.class, () -> new CaptureDecision(
                PayloadCaptureMode.METADATA_ONLY, PayloadCaptureMode.FULL_SANITIZED, "bad"));
        assertThrows(IllegalArgumentException.class, () -> new TransactionIdentifiers(
                Optional.of("secret=abc"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
    }

    private TelemetrySubmissionView envelope() {
        PartnerEvent event = (PartnerEvent) TestFixtures.submission(
                        TestFixtures.context("partner-a", "uk-dev-partner-a", "p001"), 200)
                .envelope().body();
        TelemetryEnvelope<PartnerEvent> envelope = new TelemetryEnvelope<>(
                1, UUID.randomUUID(), Instant.now(), Instant.now(), new ServiceIdentity("service", "1.0"),
                TestFixtures.context("partner-a", "uk-dev-partner-a", "p001"), Direction.OUTBOUND_TO_PARTNER,
                UUID.randomUUID(), 0, new CaptureDecision(PayloadCaptureMode.NONE, PayloadCaptureMode.NONE, "v1"),
                PayloadStatus.NOT_REQUESTED, Severity.INFO, event);
        return new TelemetrySubmissionView(envelope);
    }

    private record TelemetrySubmissionView(TelemetryEnvelope<PartnerEvent> envelope) {}
}
