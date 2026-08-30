package com.samsung.sure.partner.observability.core.model;

import com.samsung.sure.partner.observability.core.context.PartnerContext;
import com.samsung.sure.partner.observability.core.context.TrustLevel;
import com.samsung.sure.partner.observability.core.payload.PayloadStatus;
import com.samsung.sure.partner.observability.core.payload.SanitizationDisposition;
import com.samsung.sure.partner.observability.core.payload.SanitizationResult;
import com.samsung.sure.partner.observability.core.policy.PayloadCaptureMode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable, already-safe schema envelope. Schema 1 is accepted only for bounded N-1 migration. */
public record TelemetryEnvelope<T extends TelemetryRecord>(
        int schemaVersion,
        UUID eventId,
        Instant occurredAt,
        Instant observedAt,
        ServiceIdentity service,
        PartnerContext partnerContext,
        InteractionContext interactionContext,
        CaptureDecision captureDecision,
        PayloadStatus payloadStatus,
        Severity severity,
        Outcome outcome,
        T body) {

    public static final int LEGACY_SCHEMA_VERSION = 1;
    public static final int CURRENT_SCHEMA_VERSION = 2;

    public TelemetryEnvelope {
        if (schemaVersion != LEGACY_SCHEMA_VERSION && schemaVersion != CURRENT_SCHEMA_VERSION) {
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
        Objects.requireNonNull(interactionContext, "interactionContext");
        Objects.requireNonNull(captureDecision, "captureDecision");
        Objects.requireNonNull(payloadStatus, "payloadStatus");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(body, "body");
        validateSchemaBody(schemaVersion, body);
        validateInteraction(body, interactionContext);
        if (captureDecision.effectiveMode() == PayloadCaptureMode.NO_PAYLOAD) {
            throw new IllegalArgumentException("no-payload capture cannot create a telemetry envelope");
        }
        if (captureDecision.effectiveMode() == PayloadCaptureMode.METADATA_ONLY && containsCapturedPayload(body)) {
            throw new IllegalArgumentException("metadata-only capture cannot contain payload values");
        }
    }

    /** Compatibility constructor for schema-1 callers during the documented N-1 window. */
    public TelemetryEnvelope(
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
        this(
                schemaVersion,
                eventId,
                occurredAt,
                observedAt,
                service,
                partnerContext,
                legacyInteraction(direction, interactionId, eventSequence, body),
                captureDecision,
                payloadStatus,
                severity,
                legacyOutcome(body),
                body);
    }

    public TelemetryRecordType eventType() {
        return body.recordType();
    }

    public EventDomain eventDomain() {
        return body.recordType().eventDomain();
    }

    public Direction direction() {
        return interactionContext.direction();
    }

    public UUID interactionId() {
        return interactionContext.interactionId();
    }

    public int eventSequence() {
        return interactionContext.eventSequence();
    }

    private static void validateSchemaBody(int schemaVersion, TelemetryRecord body) {
        boolean legacy = body instanceof PartnerApiRequest || body instanceof PartnerApiResponse || body instanceof PartnerEvent;
        if ((schemaVersion == LEGACY_SCHEMA_VERSION) != legacy) {
            throw new IllegalArgumentException("record type does not match schema version");
        }
    }

    private static void validateInteraction(TelemetryRecord body, InteractionContext interaction) {
        if (body instanceof CallbackRequestRecord
                || body instanceof CallbackResponseRecord
                || body instanceof CallbackProcessingEventRecord) {
            if (interaction.interactionKind() != InteractionKind.CALLBACK) {
                throw new IllegalArgumentException("callback records require callback interaction context");
            }
        }
        if (body instanceof AsyncAcknowledgementRecord
                && interaction.interactionKind() != InteractionKind.ASYNC_INITIATION) {
            throw new IllegalArgumentException("acknowledgement records require async initiation context");
        }
        if (body instanceof OutboundApiResponseRecord
                && interaction.interactionKind() != InteractionKind.SYNC_OUTBOUND) {
            throw new IllegalArgumentException("outbound response records require synchronous context");
        }
        if (body instanceof OutboundApiRequestRecord request) {
            if (request.exchangeMode() == ExchangeMode.ASYNC_INITIATION
                    && (interaction.interactionKind() != InteractionKind.ASYNC_INITIATION
                            || interaction.timelineStage().orElse(null) != TimelineStage.ASYNC_REQUEST_SENT)) {
                throw new IllegalArgumentException("async requests require ASYNC_REQUEST_SENT");
            }
            if (request.exchangeMode() == ExchangeMode.SYNC
                    && interaction.interactionKind() != InteractionKind.SYNC_OUTBOUND) {
                throw new IllegalArgumentException("synchronous requests require synchronous context");
            }
        }
        if (body instanceof AsyncAcknowledgementRecord acknowledgement) {
            TimelineStage stage = interaction.timelineStage().orElse(null);
            boolean received = acknowledgement.httpStatus().isPresent();
            if (stage != (received ? TimelineStage.ASYNC_ACK_RECEIVED : TimelineStage.ASYNC_ACK_NOT_RECEIVED)) {
                throw new IllegalArgumentException("acknowledgement stage contradicts transport observation");
            }
        }
        if (body instanceof CallbackRequestRecord request) {
            TimelineStage expected = request.deliveryClassification() == DeliveryClassification.RETRY
                            || request.deliveryClassification() == DeliveryClassification.DUPLICATE
                    ? TimelineStage.CALLBACK_RETRY_RECEIVED : TimelineStage.CALLBACK_RECEIVED;
            if (interaction.timelineStage().orElse(null) != expected) {
                throw new IllegalArgumentException("callback receipt stage contradicts delivery classification");
            }
        }
        if (body instanceof CallbackResponseRecord response) {
            TimelineStage expected = response.transportOutcome() == TransportOutcome.WRITE_COMPLETED
                    ? TimelineStage.CALLBACK_RESPONSE_SENT : TimelineStage.CALLBACK_RESPONSE_WRITE_FAILED;
            if (interaction.timelineStage().orElse(null) != expected) {
                throw new IllegalArgumentException("callback response stage contradicts transport outcome");
            }
        }
        if (body instanceof CallbackProcessingEventRecord) {
            TimelineStage stage = interaction.timelineStage().orElse(null);
            if (stage != TimelineStage.CALLBACK_AUTHENTICATED
                    && stage != TimelineStage.CALLBACK_VALIDATED
                    && stage != TimelineStage.CALLBACK_PROCESSING_STARTED
                    && stage != TimelineStage.CALLBACK_PROCESSED
                    && stage != TimelineStage.CALLBACK_PROCESSING_FAILED) {
                throw new IllegalArgumentException("callback processing record requires a processing stage");
            }
        }
    }

    private static boolean containsCapturedPayload(TelemetryRecord record) {
        if (record instanceof PartnerApiRequest request) {
            return captured(request.headers()) || captured(request.query()) || captured(request.payload());
        }
        if (record instanceof PartnerApiResponse response) {
            return captured(response.headers()) || captured(response.payload());
        }
        if (record instanceof PartnerEvent event) {
            return captured(event.attributes());
        }
        if (record instanceof OutboundApiRequestRecord request) {
            return captured(request.headers()) || captured(request.query()) || captured(request.payload());
        }
        if (record instanceof OutboundApiResponseRecord response) {
            return captured(response.headers()) || captured(response.payload());
        }
        if (record instanceof AsyncAcknowledgementRecord acknowledgement) {
            return captured(acknowledgement.headers()) || captured(acknowledgement.payload());
        }
        if (record instanceof CallbackRequestRecord request) {
            return captured(request.headers()) || captured(request.payload());
        }
        if (record instanceof CallbackResponseRecord response) {
            return captured(response.headers()) || captured(response.payload());
        }
        if (record instanceof CallbackProcessingEventRecord event) {
            return captured(event.attributes());
        }
        return record instanceof PartnerBusinessEventRecord event && captured(event.attributes());
    }

    private static boolean captured(SanitizationResult result) {
        return result.disposition() == SanitizationDisposition.CAPTURED;
    }

    private static InteractionContext legacyInteraction(
            Direction direction, UUID interactionId, int eventSequence, TelemetryRecord body) {
        CorrelationIdentifiers identifiers = CorrelationIdentifiers.empty();
        if (body instanceof PartnerApiRequest request) {
            identifiers = CorrelationIdentifiers.fromLegacy(request.identifiers());
        } else if (body instanceof PartnerApiResponse response) {
            identifiers = CorrelationIdentifiers.fromLegacy(response.identifiers());
        } else if (body instanceof PartnerEvent event) {
            identifiers = CorrelationIdentifiers.fromLegacy(event.identifiers());
        }
        InteractionKind kind = direction == Direction.INBOUND_FROM_PARTNER
                ? InteractionKind.CALLBACK
                : InteractionKind.SYNC_OUTBOUND;
        return new InteractionContext(
                kind,
                direction,
                interactionId,
                eventSequence,
                kind == InteractionKind.CALLBACK ? Optional.of(UUID.randomUUID()) : Optional.empty(),
                "legacy-v1",
                identifiers,
                Optional.empty());
    }

    private static Outcome legacyOutcome(TelemetryRecord body) {
        if (body instanceof PartnerApiResponse response) {
            return response.outcome();
        }
        if (body instanceof PartnerEvent event) {
            return event.outcome();
        }
        return Outcome.UNKNOWN;
    }
}
