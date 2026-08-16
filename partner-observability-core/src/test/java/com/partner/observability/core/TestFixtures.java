package com.partner.observability.core;

import com.partner.observability.core.context.DeploymentEnvironment;
import com.partner.observability.core.context.PartnerContext;
import com.partner.observability.core.context.PartnerContextResolver;
import com.partner.observability.core.dispatch.TelemetryChannel;
import com.partner.observability.core.dispatch.TelemetryPriority;
import com.partner.observability.core.dispatch.TelemetrySubmission;
import com.partner.observability.core.model.CaptureDecision;
import com.partner.observability.core.model.Direction;
import com.partner.observability.core.model.Outcome;
import com.partner.observability.core.model.PartnerEvent;
import com.partner.observability.core.model.ServiceIdentity;
import com.partner.observability.core.model.Severity;
import com.partner.observability.core.model.TelemetryEnvelope;
import com.partner.observability.core.model.TransactionIdentifiers;
import com.partner.observability.core.payload.PayloadStatus;
import com.partner.observability.core.payload.SanitizationResult;
import com.partner.observability.core.policy.PayloadCaptureMode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class TestFixtures {
    private TestFixtures() {}

    public static PartnerContext context(String partner, String tenant, String slot) {
        return new TestResolver(partner, tenant, slot).resolve("authenticated-subject").orElseThrow();
    }

    public static TelemetrySubmission submission(PartnerContext context, int bytes) {
        return submission(
                context,
                bytes,
                SanitizationResult.omitted(PayloadStatus.NOT_REQUESTED),
                PayloadCaptureMode.METADATA_ONLY,
                PayloadStatus.NOT_REQUESTED);
    }

    public static TelemetrySubmission submission(
            PartnerContext context,
            int bytes,
            SanitizationResult attributes,
            PayloadCaptureMode captureMode,
            PayloadStatus payloadStatus) {
        PartnerEvent body = new PartnerEvent(
                "application_submitted",
                "APPLICATION",
                Outcome.SUCCESS,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                java.util.OptionalInt.empty(),
                Optional.empty(),
                Optional.of("SKU-1"),
                Optional.of("LOAN"),
                attributes,
                new TransactionIdentifiers(
                        Optional.of("APP-SHARED-1"),
                        Optional.empty(),
                        Optional.of("CORR-1"),
                        Optional.of(UUID.randomUUID().toString()),
                        Optional.empty()));
        TelemetryEnvelope<PartnerEvent> envelope = new TelemetryEnvelope<>(
                TelemetryEnvelope.CURRENT_SCHEMA_VERSION,
                UUID.randomUUID(),
                Instant.parse("2026-08-16T12:00:00Z"),
                Instant.parse("2026-08-16T12:00:00.001Z"),
                new ServiceIdentity("fixture-service", "1.0.0"),
                context,
                Direction.OUTBOUND_TO_PARTNER,
                UUID.randomUUID(),
                1,
                new CaptureDecision(
                        captureMode, captureMode, "test-v1"),
                payloadStatus,
                Severity.INFO,
                body);
        return new TelemetrySubmission(envelope, bytes, TelemetryPriority.NORMAL, TelemetryChannel.EVENT);
    }

    private static final class TestResolver extends PartnerContextResolver<String> {
        private final String partner;
        private final String tenant;
        private final String slot;

        private TestResolver(String partner, String tenant, String slot) {
            this.partner = partner;
            this.tenant = tenant;
            this.slot = slot;
        }

        @Override
        protected Optional<ResolvedPartner> resolveAuthenticated(String authenticatedServerSubject) {
            if (!"authenticated-subject".equals(authenticatedServerSubject)) {
                return Optional.empty();
            }
            return Optional.of(authenticatedPartner(
                    "uk", DeploymentEnvironment.DEV, partner, tenant, slot, "fixture-auth"));
        }
    }
}
