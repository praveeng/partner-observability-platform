package com.partner.observability.core.model;

import com.partner.observability.core.context.PartnerContext;
import com.partner.observability.core.context.TrustLevel;
import com.partner.observability.core.payload.PayloadStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TelemetryEnvelope<T extends TelemetryRecord>(
        int schemaVersion,
        UUID eventId,
        Instant occurredAt,
        Instant observedAt,
        ServiceIdentity service,
        PartnerContext partnerContext,
        Direction direction,
        UUID interactionId,
        int eventSequence,
        CaptureDecision captureDecision,
        PayloadStatus payloadStatus,
        Severity severity,
        T body) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public TelemetryEnvelope {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported schemaVersion");
        }
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(partnerContext, "partnerContext");
        if (partnerContext.trustLevel() != TrustLevel.AUTHENTICATED_SERVER) {
            throw new IllegalArgumentException("partnerContext is not trusted");
        }
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(interactionId, "interactionId");
        if (eventSequence < 0) {
            throw new IllegalArgumentException("eventSequence cannot be negative");
        }
        Objects.requireNonNull(captureDecision, "captureDecision");
        Objects.requireNonNull(payloadStatus, "payloadStatus");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(body, "body");
    }
}
