package com.partner.observability.autoconfigure;

import com.partner.observability.core.dispatch.BoundedAsyncDispatcher;
import com.partner.observability.core.dispatch.TelemetryChannel;
import com.partner.observability.core.dispatch.TelemetryPriority;
import com.partner.observability.core.dispatch.TelemetrySubmission;
import com.partner.observability.core.model.AcknowledgementOutcome;
import com.partner.observability.core.model.AsyncAcknowledgementRecord;
import com.partner.observability.core.model.CaptureDecision;
import com.partner.observability.core.model.CorrelationIdentifiers;
import com.partner.observability.core.model.Direction;
import com.partner.observability.core.model.ExchangeMode;
import com.partner.observability.core.model.InteractionContext;
import com.partner.observability.core.model.InteractionKind;
import com.partner.observability.core.model.Outcome;
import com.partner.observability.core.model.OutboundApiRequestRecord;
import com.partner.observability.core.model.OutboundApiResponseRecord;
import com.partner.observability.core.model.PartnerHttpMethod;
import com.partner.observability.core.model.ProcessingDisposition;
import com.partner.observability.core.model.ServiceIdentity;
import com.partner.observability.core.model.Severity;
import com.partner.observability.core.model.StatusClass;
import com.partner.observability.core.model.TelemetryEnvelope;
import com.partner.observability.core.model.TelemetryRecord;
import com.partner.observability.core.model.TimelineStage;
import com.partner.observability.core.model.TransportState;
import com.partner.observability.core.payload.PayloadStatus;
import com.partner.observability.core.payload.SanitizationDisposition;
import com.partner.observability.core.payload.SanitizationResult;
import com.partner.observability.core.policy.ObservabilityKillSwitches;
import com.partner.observability.core.policy.PayloadCaptureMode;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

public final class PartnerObservationEngine {

    private static final SanitizationResult NO_VALUES = SanitizationResult.omitted(PayloadStatus.NOT_REQUESTED);

    private final PartnerObservabilityProperties properties;
    private final ConfiguredObservationRegistry registry;
    private final ObservabilityKillSwitches killSwitches;
    private final BoundedAsyncDispatcher dispatcher;
    private final Optional<SafeBodyCapture> bodyCapture;
    private final ServiceIdentity serviceIdentity;
    private final ObservationMetrics metrics;

    PartnerObservationEngine(
            PartnerObservabilityProperties properties,
            ConfiguredObservationRegistry registry,
            ObservabilityKillSwitches killSwitches,
            BoundedAsyncDispatcher dispatcher,
            Optional<SafeBodyCapture> bodyCapture,
            ObservationMetrics metrics) {
        this.properties = properties;
        this.registry = registry;
        this.killSwitches = killSwitches;
        this.dispatcher = dispatcher;
        this.bodyCapture = bodyCapture;
        this.serviceIdentity = new ServiceIdentity(properties.getServiceName(), properties.getServiceVersion());
        this.metrics = metrics;
    }

    public Optional<OutboundObservation> startOutbound(
            URI uri,
            String method,
            Object body,
            boolean bodySupported,
            String contentType,
            OptionalLong declaredSize,
            int attempt) {
        if (!properties.isEnabled() || !properties.isEventsEnabled()) {
            return Optional.empty();
        }
        Optional<ObservationDefinition> matched = registry.outbound(method, uri);
        if (matched.isEmpty()) {
            return Optional.empty();
        }
        ObservationDefinition definition = matched.get();
        try {
            PayloadCaptureMode mode = effectiveMode(definition);
            if (mode == PayloadCaptureMode.NO_PAYLOAD) {
                return Optional.empty();
            }
            CapturedBody captured = capture(
                    definition, ObservationLeg.OUTBOUND_REQUEST, body, bodySupported,
                    contentType, declaredSize, mode);
            UUID interactionId = UUID.randomUUID();
            Instant now = Instant.now();
            InteractionKind kind = definition.exchangeMode() == ExchangeMode.ASYNC_INITIATION
                    ? InteractionKind.ASYNC_INITIATION
                    : InteractionKind.SYNC_OUTBOUND;
            InteractionContext interaction = new InteractionContext(
                    kind, Direction.OUTBOUND_TO_PARTNER, interactionId, 0, Optional.empty(),
                    definition.correlationProfile(), captured.identifiers(),
                    kind == InteractionKind.ASYNC_INITIATION
                            ? Optional.of(TimelineStage.ASYNC_REQUEST_SENT)
                            : Optional.empty());
            OutboundApiRequestRecord request = new OutboundApiRequestRecord(
                    definition.name(), definition.path(), definition.exchangeMode(), method(method),
                    Math.max(1, Math.min(10, attempt)), normalizedContentType(contentType), declaredSize,
                    NO_VALUES, NO_VALUES, safeResult(captured.payload()), TransportState.DELEGATED);
            submit(definition, envelope(
                    definition, now, interaction, mode, captured.payload().status(), Outcome.UNKNOWN, request));
            return Optional.of(new OutboundObservation(
                    this, definition, interactionId, now, System.nanoTime(), captured.identifiers()));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    public Optional<String> resolveOutboundApiName(URI uri, String method) {
        try {
            return registry.outbound(method, uri).map(ObservationDefinition::name);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    void completeOutbound(
            ObservationDefinition definition,
            UUID interactionId,
            Instant startedAt,
            long startedNanos,
            CorrelationIdentifiers requestIdentifiers,
            int status,
            Object body,
            boolean bodySupported,
            String contentType,
            OptionalLong declaredSize) {
        try {
            PayloadCaptureMode mode = effectiveMode(definition);
            if (mode == PayloadCaptureMode.NO_PAYLOAD) {
                return;
            }
            ObservationLeg leg = definition.exchangeMode() == ExchangeMode.ASYNC_INITIATION
                    ? ObservationLeg.ASYNC_ACKNOWLEDGEMENT
                    : ObservationLeg.OUTBOUND_RESPONSE;
            CapturedBody captured = capture(definition, leg, body, bodySupported, contentType, declaredSize, mode);
            CorrelationIdentifiers identifiers = requestIdentifiers.merge(captured.identifiers());
            long duration = elapsedMillis(startedNanos);
            StatusClass statusClass = statusClass(status);
            Outcome outcome = outcome(statusClass);
            InteractionKind kind = definition.exchangeMode() == ExchangeMode.ASYNC_INITIATION
                    ? InteractionKind.ASYNC_INITIATION
                    : InteractionKind.SYNC_OUTBOUND;
            Optional<TimelineStage> stage = kind == InteractionKind.ASYNC_INITIATION
                    ? Optional.of(TimelineStage.ASYNC_ACK_RECEIVED)
                    : Optional.empty();
            InteractionContext interaction = new InteractionContext(
                    kind, Direction.OUTBOUND_TO_PARTNER, interactionId, 1, Optional.empty(),
                    definition.correlationProfile(), identifiers, stage);
            TelemetryRecord record;
            AcknowledgementOutcome acknowledgement = null;
            if (kind == InteractionKind.ASYNC_INITIATION) {
                acknowledgement = status >= 200 && status < 300
                        ? AcknowledgementOutcome.ACCEPTED
                        : AcknowledgementOutcome.REJECTED;
                record = new AsyncAcknowledgementRecord(
                        definition.name(), OptionalInt.of(status), statusClass, acknowledgement, outcome,
                        duration, acknowledgement == AcknowledgementOutcome.ACCEPTED
                                ? ProcessingDisposition.PARTNER_PROCESSING_EXPECTED
                                : ProcessingDisposition.TERMINAL_REJECTION,
                        Optional.empty(), normalizedContentType(contentType), declaredSize,
                        NO_VALUES, safeResult(captured.payload()));
            } else {
                record = new OutboundApiResponseRecord(
                        definition.name(), OptionalInt.of(status), statusClass, outcome, duration,
                        Optional.empty(), normalizedContentType(contentType), declaredSize,
                        NO_VALUES, safeResult(captured.payload()));
            }
            submit(definition, envelope(
                    definition, Instant.now(), interaction, mode, captured.payload().status(), outcome, record));
            metrics.outboundCompleted(definition, outcome, statusClass, duration, acknowledgement);
        } catch (RuntimeException ignored) {
            // Observation cannot alter the HTTP client's result.
        }
    }

    void failOutbound(
            ObservationDefinition definition,
            UUID interactionId,
            Instant startedAt,
            long startedNanos,
            CorrelationIdentifiers identifiers,
            Throwable failure) {
        try {
            PayloadCaptureMode mode = effectiveMode(definition);
            if (mode == PayloadCaptureMode.NO_PAYLOAD) {
                return;
            }
            boolean cancelled = failure instanceof CancellationException || failure instanceof InterruptedException;
            boolean timeout = failure instanceof SocketTimeoutException || failure instanceof TimeoutException
                    || failure.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT).contains("timeout");
            StatusClass statusClass = cancelled ? StatusClass.CANCELLED : StatusClass.IO_ERROR;
            Outcome outcome = cancelled ? Outcome.CANCELLED : Outcome.TECHNICAL_FAILURE;
            String failureCode = cancelled ? "cancelled" : timeout ? "timeout" : "transport_failure";
            InteractionKind kind = definition.exchangeMode() == ExchangeMode.ASYNC_INITIATION
                    ? InteractionKind.ASYNC_INITIATION
                    : InteractionKind.SYNC_OUTBOUND;
            InteractionContext interaction = new InteractionContext(
                    kind, Direction.OUTBOUND_TO_PARTNER, interactionId, 1, Optional.empty(),
                    definition.correlationProfile(), identifiers,
                    kind == InteractionKind.ASYNC_INITIATION
                            ? Optional.of(TimelineStage.ASYNC_ACK_NOT_RECEIVED)
                            : Optional.empty());
            long duration = elapsedMillis(startedNanos);
            TelemetryRecord record;
            AcknowledgementOutcome acknowledgement = null;
            if (kind == InteractionKind.ASYNC_INITIATION) {
                acknowledgement = cancelled
                        ? AcknowledgementOutcome.CANCELLED
                        : timeout ? AcknowledgementOutcome.NO_ACK_TIMEOUT : AcknowledgementOutcome.TRANSPORT_FAILURE;
                record = new AsyncAcknowledgementRecord(
                        definition.name(), OptionalInt.empty(), statusClass, acknowledgement, outcome,
                        duration, ProcessingDisposition.UNKNOWN, Optional.of(failureCode), Optional.empty(),
                        OptionalLong.empty(), NO_VALUES, NO_VALUES);
            } else {
                record = new OutboundApiResponseRecord(
                        definition.name(), OptionalInt.empty(), statusClass, outcome, duration,
                        Optional.of(failureCode), Optional.empty(), OptionalLong.empty(), NO_VALUES, NO_VALUES);
            }
            submit(definition, envelope(
                    definition, Instant.now(), interaction, mode, PayloadStatus.NOT_REQUESTED, outcome, record));
            metrics.outboundCompleted(definition, outcome, statusClass, duration, acknowledgement);
        } catch (RuntimeException ignored) {
            // Observation cannot replace the business exception.
        }
    }

    public CallbackObservation startCallback(
            ObservationDefinition definition,
            Instant receivedAt,
            String method,
            String contentType,
            OptionalLong declaredSize) {
        return new CallbackObservation(
                this, definition, receivedAt, method(method), contentType, declaredSize,
                UUID.randomUUID(), UUID.randomUUID(), System.nanoTime());
    }

    ObservationMetrics metrics() {
        return metrics;
    }

    PayloadCaptureMode effectiveMode(ObservationDefinition definition) {
        return killSwitches.snapshot().effectiveCaptureMode(definition.captureMode());
    }

    CapturedBody capture(
            ObservationDefinition definition,
            ObservationLeg leg,
            Object candidate,
            boolean supported,
            String contentType,
            OptionalLong declaredSize,
            PayloadCaptureMode mode) {
        if (!supported || bodyCapture.isEmpty()) {
            return SafeBodyCapture.unsupported(mode);
        }
        return bodyCapture.get().capture(definition, leg, candidate, contentType, declaredSize, mode);
    }

    <T extends TelemetryRecord> TelemetryEnvelope<T> envelope(
            ObservationDefinition definition,
            Instant occurredAt,
            InteractionContext interaction,
            PayloadCaptureMode mode,
            PayloadStatus status,
            Outcome outcome,
            T body) {
        return new TelemetryEnvelope<>(
                TelemetryEnvelope.CURRENT_SCHEMA_VERSION, UUID.randomUUID(), occurredAt, Instant.now(),
                serviceIdentity, definition.partnerContext(), interaction,
                new CaptureDecision(definition.captureMode(), mode, properties.getPolicyVersion()), status,
                severity(outcome), outcome, body);
    }

    void submit(ObservationDefinition definition, TelemetryEnvelope<?> envelope) {
        TelemetryPriority priority = priority(envelope);
        boolean accepted = dispatcher.submitSafely(() -> new TelemetrySubmission(
                envelope, estimatedSize(envelope), priority, TelemetryChannel.EVENT));
        metrics.submitted(definition, envelope.body().recordType(), accepted, priority);
    }

    static SanitizationResult safeResult(SanitizationResult result) {
        if (result.disposition() == SanitizationDisposition.REJECTED) {
            return SanitizationResult.omitted(result.status());
        }
        return result;
    }

    static StatusClass statusClass(int status) {
        return switch (status / 100) {
            case 1 -> StatusClass.ONE_XX;
            case 2 -> StatusClass.TWO_XX;
            case 3 -> StatusClass.THREE_XX;
            case 4 -> StatusClass.FOUR_XX;
            case 5 -> StatusClass.FIVE_XX;
            default -> StatusClass.UNKNOWN;
        };
    }

    static Outcome outcome(StatusClass status) {
        return switch (status) {
            case TWO_XX, THREE_XX -> Outcome.SUCCESS;
            case FOUR_XX -> Outcome.BUSINESS_REJECTED;
            case FIVE_XX, IO_ERROR -> Outcome.TECHNICAL_FAILURE;
            case CANCELLED -> Outcome.CANCELLED;
            default -> Outcome.UNKNOWN;
        };
    }

    static PartnerHttpMethod method(String method) {
        if (method == null) { return PartnerHttpMethod.OTHER; }
        try { return PartnerHttpMethod.valueOf(method.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException exception) { return PartnerHttpMethod.OTHER; }
    }

    static Optional<String> normalizedContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) { return Optional.empty(); }
        String value = contentType.toLowerCase(java.util.Locale.ROOT);
        int separator = value.indexOf(';');
        return Optional.of((separator >= 0 ? value.substring(0, separator) : value).trim());
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private Severity severity(Outcome outcome) {
        return switch (outcome) {
            case SUCCESS -> Severity.INFO;
            case BUSINESS_REJECTED, CANCELLED, UNKNOWN -> Severity.WARN;
            case TECHNICAL_FAILURE -> Severity.ERROR;
        };
    }

    private int estimatedSize(TelemetryEnvelope<?> envelope) {
        int payloadBytes = payloadBytes(envelope.body());
        return Math.min(64 * 1024, Math.max(512, 1024 + payloadBytes));
    }

    private int payloadBytes(TelemetryRecord record) {
        SanitizationResult result = null;
        if (record instanceof OutboundApiRequestRecord value) result = value.payload();
        else if (record instanceof OutboundApiResponseRecord value) result = value.payload();
        else if (record instanceof AsyncAcknowledgementRecord value) result = value.payload();
        else if (record instanceof com.partner.observability.core.model.CallbackRequestRecord value) result = value.payload();
        else if (record instanceof com.partner.observability.core.model.CallbackResponseRecord value) result = value.payload();
        if (result == null || result.payload().isEmpty()) return 0;
        return result.payload().get().jsonUtf8Bytes();
    }

    private TelemetryPriority priority(TelemetryEnvelope<?> envelope) {
        return envelope.outcome() == Outcome.SUCCESS || envelope.outcome() == Outcome.UNKNOWN
                ? TelemetryPriority.NORMAL : TelemetryPriority.HIGH;
    }
}
