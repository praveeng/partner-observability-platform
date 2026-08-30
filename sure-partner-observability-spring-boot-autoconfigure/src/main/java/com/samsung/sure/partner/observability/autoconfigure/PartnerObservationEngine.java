package com.samsung.sure.partner.observability.autoconfigure;

import com.samsung.sure.partner.observability.core.dispatch.BoundedAsyncDispatcher;
import com.samsung.sure.partner.observability.core.dispatch.TelemetryChannel;
import com.samsung.sure.partner.observability.core.dispatch.TelemetryPriority;
import com.samsung.sure.partner.observability.core.dispatch.TelemetrySubmission;
import com.samsung.sure.partner.observability.core.model.AcknowledgementOutcome;
import com.samsung.sure.partner.observability.core.model.AsyncAcknowledgementRecord;
import com.samsung.sure.partner.observability.core.model.CaptureDecision;
import com.samsung.sure.partner.observability.core.model.CorrelationIdentifiers;
import com.samsung.sure.partner.observability.core.model.Direction;
import com.samsung.sure.partner.observability.core.model.ExchangeMode;
import com.samsung.sure.partner.observability.core.model.InteractionContext;
import com.samsung.sure.partner.observability.core.model.InteractionKind;
import com.samsung.sure.partner.observability.core.model.HttpResult;
import com.samsung.sure.partner.observability.core.model.Outcome;
import com.samsung.sure.partner.observability.core.model.OutboundApiRequestRecord;
import com.samsung.sure.partner.observability.core.model.OutboundApiResponseRecord;
import com.samsung.sure.partner.observability.core.model.PartnerHttpMethod;
import com.samsung.sure.partner.observability.core.model.ProcessingDisposition;
import com.samsung.sure.partner.observability.core.model.ServiceIdentity;
import com.samsung.sure.partner.observability.core.model.Severity;
import com.samsung.sure.partner.observability.core.model.StatusClass;
import com.samsung.sure.partner.observability.core.model.TelemetryEnvelope;
import com.samsung.sure.partner.observability.core.model.TelemetryRecord;
import com.samsung.sure.partner.observability.core.model.TimelineStage;
import com.samsung.sure.partner.observability.core.model.TransportFailureClass;
import com.samsung.sure.partner.observability.core.model.TransportSecurity;
import com.samsung.sure.partner.observability.core.model.TransportState;
import com.samsung.sure.partner.observability.core.payload.PayloadStatus;
import com.samsung.sure.partner.observability.core.payload.SanitizationDisposition;
import com.samsung.sure.partner.observability.core.payload.SanitizationResult;
import com.samsung.sure.partner.observability.core.policy.ObservabilityKillSwitches;
import com.samsung.sure.partner.observability.core.policy.PayloadCaptureMode;
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
        if (!properties.isEnabled() || (!properties.isEventsEnabled() && !properties.isMetricsEnabled())) {
            return Optional.empty();
        }
        Optional<ObservationDefinition> matched = registry.outbound(method, uri);
        if (matched.isEmpty()) {
            return Optional.empty();
        }
        ObservationDefinition definition = matched.get();
        Optional<PartnerObservation> explicit = PartnerObservations.current(definition);
        if (explicit.isPresent()) {
            Optional<OutboundObservation> observation = explicit.get().transportStarted(uri, attempt);
            observation.ifPresent(ignored -> metrics.outboundStarted(definition, attempt));
            return observation;
        }
        try {
            PayloadCaptureMode mode = effectiveMode(definition);
            boolean emitEvent = properties.isEventsEnabled() && mode != PayloadCaptureMode.NO_PAYLOAD;
            CapturedBody captured = emitEvent
                    ? capture(definition, ObservationLeg.OUTBOUND_REQUEST, body, bodySupported,
                            contentType, declaredSize, mode)
                    : new CapturedBody(NO_VALUES, CorrelationIdentifiers.empty());
            UUID interactionId = UUID.randomUUID();
            Instant now = Instant.now();
            Optional<TransportSecurity> security = transportSecurity(uri);
            if (emitEvent) {
                InteractionKind kind = definition.exchangeMode() == ExchangeMode.ASYNC_INITIATION
                        ? InteractionKind.ASYNC_INITIATION : InteractionKind.SYNC_OUTBOUND;
                InteractionContext interaction = new InteractionContext(
                        kind, Direction.OUTBOUND_TO_PARTNER, interactionId, 0, Optional.empty(),
                        definition.correlationProfile(), captured.identifiers(),
                        kind == InteractionKind.ASYNC_INITIATION
                                ? Optional.of(TimelineStage.ASYNC_REQUEST_SENT) : Optional.empty());
                OutboundApiRequestRecord request = new OutboundApiRequestRecord(
                        definition.name(), definition.path(), definition.exchangeMode(), method(method),
                        Math.max(1, Math.min(10, attempt)), normalizedContentType(contentType), declaredSize,
                        NO_VALUES, NO_VALUES, safeResult(captured.payload()), TransportState.DELEGATED, security);
                submit(definition, envelope(
                        definition, now, interaction, mode, captured.payload().status(), Outcome.UNKNOWN, request));
            }
            OutboundObservation observation = new OutboundObservation(
                    this, definition, interactionId, now, System.nanoTime(), captured.identifiers(),
                    security);
            metrics.outboundStarted(definition, attempt);
            return Optional.of(observation);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    void emitExplicitRequest(
            ObservationDefinition definition,
            UUID interactionId,
            Instant occurredAt,
            CapturedBody captured,
            Optional<TransportSecurity> transportSecurity,
            int attempt) {
        try {
            PayloadCaptureMode mode = effectiveMode(definition);
            if (!properties.isEventsEnabled() || mode == PayloadCaptureMode.NO_PAYLOAD) return;
            CapturedBody effective = forMode(captured, mode);
            InteractionKind kind = definition.exchangeMode() == ExchangeMode.ASYNC_INITIATION
                    ? InteractionKind.ASYNC_INITIATION : InteractionKind.SYNC_OUTBOUND;
            InteractionContext interaction = new InteractionContext(
                    kind, Direction.OUTBOUND_TO_PARTNER, interactionId, 0, Optional.empty(),
                    definition.correlationProfile(), effective.identifiers(),
                    kind == InteractionKind.ASYNC_INITIATION
                            ? Optional.of(TimelineStage.ASYNC_REQUEST_SENT) : Optional.empty());
            OutboundApiRequestRecord request = new OutboundApiRequestRecord(
                    definition.name(), definition.path(), definition.exchangeMode(), method(definition.method()),
                    Math.max(1, Math.min(10, attempt)), Optional.of("application/json"), OptionalLong.empty(),
                    NO_VALUES, NO_VALUES, safeResult(effective.payload()), TransportState.DELEGATED,
                    transportSecurity);
            submit(definition, envelope(
                    definition, occurredAt, interaction, mode, effective.payload().status(),
                    Outcome.UNKNOWN, request));
        } catch (RuntimeException ignored) {
            // Explicit capture cannot alter encryption or transport behavior.
        }
    }

    void completeExplicitOutbound(
            ObservationDefinition definition,
            UUID interactionId,
            long startedNanos,
            CorrelationIdentifiers requestIdentifiers,
            Optional<TransportSecurity> transportSecurity,
            int status,
            CapturedBody captured) {
        long duration = elapsedMillis(startedNanos);
        StatusClass statusClass = statusClass(status);
        Outcome outcome = outcome(statusClass);
        AcknowledgementOutcome acknowledgement = definition.exchangeMode() == ExchangeMode.ASYNC_INITIATION
                ? status >= 200 && status < 300
                        ? AcknowledgementOutcome.ACCEPTED : AcknowledgementOutcome.REJECTED
                : null;
        recordOutboundCompletion(
                definition, outcome, statusClass, duration, acknowledgement, httpResult(statusClass));
        try {
            PayloadCaptureMode mode = effectiveMode(definition);
            if (!properties.isEventsEnabled() || mode == PayloadCaptureMode.NO_PAYLOAD) return;
            CapturedBody effective = forMode(captured, mode);
            CorrelationIdentifiers identifiers = requestIdentifiers.merge(effective.identifiers());
            InteractionKind kind = definition.exchangeMode() == ExchangeMode.ASYNC_INITIATION
                    ? InteractionKind.ASYNC_INITIATION : InteractionKind.SYNC_OUTBOUND;
            InteractionContext interaction = new InteractionContext(
                    kind, Direction.OUTBOUND_TO_PARTNER, interactionId, 1, Optional.empty(),
                    definition.correlationProfile(), identifiers,
                    kind == InteractionKind.ASYNC_INITIATION
                            ? Optional.of(TimelineStage.ASYNC_ACK_RECEIVED) : Optional.empty());
            TelemetryRecord record;
            if (kind == InteractionKind.ASYNC_INITIATION) {
                record = new AsyncAcknowledgementRecord(
                        definition.name(), OptionalInt.of(status), statusClass, acknowledgement, outcome,
                        duration, acknowledgement == AcknowledgementOutcome.ACCEPTED
                                ? ProcessingDisposition.PARTNER_PROCESSING_EXPECTED
                                : ProcessingDisposition.TERMINAL_REJECTION,
                        Optional.empty(), transportSecurity, Optional.empty(), Optional.of("application/json"),
                        OptionalLong.empty(), NO_VALUES, safeResult(effective.payload()));
            } else {
                record = new OutboundApiResponseRecord(
                        definition.name(), OptionalInt.of(status), statusClass, outcome, duration,
                        Optional.empty(), transportSecurity, Optional.empty(), Optional.of("application/json"),
                        OptionalLong.empty(), NO_VALUES, safeResult(effective.payload()));
            }
            submit(definition, envelope(
                    definition, Instant.now(), interaction, mode, effective.payload().status(), outcome, record));
        } catch (RuntimeException ignored) {
            // Explicit capture cannot alter decryption or the business result.
        }
    }

    private CapturedBody forMode(CapturedBody captured, PayloadCaptureMode mode) {
        if (mode == PayloadCaptureMode.FULL_SANITIZED) {
            return new CapturedBody(safeResult(captured.payload()), captured.identifiers());
        }
        return new CapturedBody(NO_VALUES, captured.identifiers());
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
            Optional<TransportSecurity> transportSecurity,
            int status,
            Object body,
            boolean bodySupported,
            String contentType,
            OptionalLong declaredSize) {
        long duration = elapsedMillis(startedNanos);
        StatusClass statusClass = statusClass(status);
        Outcome outcome = outcome(statusClass);
        AcknowledgementOutcome acknowledgement = definition.exchangeMode() == ExchangeMode.ASYNC_INITIATION
                ? status >= 200 && status < 300
                        ? AcknowledgementOutcome.ACCEPTED : AcknowledgementOutcome.REJECTED
                : null;
        recordOutboundCompletion(
                definition, outcome, statusClass, duration, acknowledgement, httpResult(statusClass));
        try {
            PayloadCaptureMode mode = effectiveMode(definition);
            if (!properties.isEventsEnabled() || mode == PayloadCaptureMode.NO_PAYLOAD) return;
            ObservationLeg leg = definition.exchangeMode() == ExchangeMode.ASYNC_INITIATION
                    ? ObservationLeg.ASYNC_ACKNOWLEDGEMENT
                    : ObservationLeg.OUTBOUND_RESPONSE;
            CapturedBody captured = capture(definition, leg, body, bodySupported, contentType, declaredSize, mode);
            CorrelationIdentifiers identifiers = requestIdentifiers.merge(captured.identifiers());
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
            if (kind == InteractionKind.ASYNC_INITIATION) {
                record = new AsyncAcknowledgementRecord(
                        definition.name(), OptionalInt.of(status), statusClass, acknowledgement, outcome,
                        duration, acknowledgement == AcknowledgementOutcome.ACCEPTED
                                ? ProcessingDisposition.PARTNER_PROCESSING_EXPECTED
                                : ProcessingDisposition.TERMINAL_REJECTION,
                        Optional.empty(), transportSecurity, Optional.empty(),
                        normalizedContentType(contentType), declaredSize,
                        NO_VALUES, safeResult(captured.payload()));
            } else {
                record = new OutboundApiResponseRecord(
                        definition.name(), OptionalInt.of(status), statusClass, outcome, duration,
                        Optional.empty(), transportSecurity, Optional.empty(),
                        normalizedContentType(contentType), declaredSize,
                        NO_VALUES, safeResult(captured.payload()));
            }
            submit(definition, envelope(
                    definition, Instant.now(), interaction, mode, captured.payload().status(), outcome, record));
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
            Optional<TransportSecurity> transportSecurity,
            Throwable failure) {
        boolean cancelled = failure instanceof CancellationException || failure instanceof InterruptedException;
        boolean timeout = failure instanceof SocketTimeoutException || failure instanceof TimeoutException
                || failure.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT).contains("timeout");
        StatusClass statusClass = cancelled ? StatusClass.CANCELLED : StatusClass.IO_ERROR;
        Outcome outcome = cancelled ? Outcome.CANCELLED : Outcome.TECHNICAL_FAILURE;
        long duration = elapsedMillis(startedNanos);
        AcknowledgementOutcome acknowledgement = definition.exchangeMode() == ExchangeMode.ASYNC_INITIATION
                ? cancelled
                        ? AcknowledgementOutcome.CANCELLED
                        : timeout ? AcknowledgementOutcome.NO_ACK_TIMEOUT
                                : AcknowledgementOutcome.TRANSPORT_FAILURE
                : null;
        recordOutboundCompletion(
                definition, outcome, statusClass, duration, acknowledgement,
                cancelled ? HttpResult.CANCELLED
                        : timeout ? HttpResult.TIMEOUT : HttpResult.CONNECTION_FAILURE);
        Optional<TransportFailureClass> transportFailure = transportSecurity.isPresent()
                ? TransportFailureClassifier.classify(failure) : Optional.empty();
        transportFailure.ifPresent(value -> {
            try {
                metrics.transportSecurityFailure(definition, value);
            } catch (RuntimeException ignored) {
                // A custom meter implementation cannot affect the business failure.
            }
        });
        try {
            PayloadCaptureMode mode = effectiveMode(definition);
            if (!properties.isEventsEnabled() || mode == PayloadCaptureMode.NO_PAYLOAD) return;
            String failureCode = cancelled ? "cancelled" : timeout ? "timeout" : "transport_failure";
            if (transportFailure.isPresent()) {
                failureCode = transportFailure.get().name().toLowerCase(java.util.Locale.ROOT);
            }
            InteractionKind kind = definition.exchangeMode() == ExchangeMode.ASYNC_INITIATION
                    ? InteractionKind.ASYNC_INITIATION
                    : InteractionKind.SYNC_OUTBOUND;
            InteractionContext interaction = new InteractionContext(
                    kind, Direction.OUTBOUND_TO_PARTNER, interactionId, 1, Optional.empty(),
                    definition.correlationProfile(), identifiers,
                    kind == InteractionKind.ASYNC_INITIATION
                            ? Optional.of(TimelineStage.ASYNC_ACK_NOT_RECEIVED)
                            : Optional.empty());
            TelemetryRecord record;
            if (kind == InteractionKind.ASYNC_INITIATION) {
                record = new AsyncAcknowledgementRecord(
                        definition.name(), OptionalInt.empty(), statusClass, acknowledgement, outcome,
                        duration, ProcessingDisposition.UNKNOWN, Optional.of(failureCode),
                        transportSecurity, transportFailure, Optional.empty(),
                        OptionalLong.empty(), NO_VALUES, NO_VALUES);
            } else {
                record = new OutboundApiResponseRecord(
                        definition.name(), OptionalInt.empty(), statusClass, outcome, duration,
                        Optional.of(failureCode), transportSecurity, transportFailure,
                        Optional.empty(), OptionalLong.empty(), NO_VALUES, NO_VALUES);
            }
            submit(definition, envelope(
                    definition, Instant.now(), interaction, mode, PayloadStatus.NOT_REQUESTED, outcome, record));
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
        CallbackObservation observation = new CallbackObservation(
                this, definition, receivedAt, method(method), contentType, declaredSize,
                UUID.randomUUID(), UUID.randomUUID(), System.nanoTime());
        metrics.callbackStarted(definition);
        return observation;
    }

    ObservationMetrics metrics() {
        return metrics;
    }

    private void recordOutboundCompletion(
            ObservationDefinition definition,
            Outcome outcome,
            StatusClass status,
            long duration,
            AcknowledgementOutcome acknowledgement,
            HttpResult result) {
        try {
            metrics.outboundCompleted(definition, outcome, status, duration, acknowledgement, result);
        } catch (RuntimeException ignored) {
            // A custom meter implementation cannot affect business completion.
        }
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

    static HttpResult httpResult(StatusClass status) {
        return switch (status) {
            case ONE_XX -> HttpResult.HTTP_1XX;
            case TWO_XX -> HttpResult.HTTP_2XX;
            case THREE_XX -> HttpResult.HTTP_3XX;
            case FOUR_XX -> HttpResult.HTTP_4XX;
            case FIVE_XX -> HttpResult.HTTP_5XX;
            case CANCELLED -> HttpResult.CANCELLED;
            default -> HttpResult.UNKNOWN;
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

    private static Optional<TransportSecurity> transportSecurity(URI uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme())
                ? Optional.of(TransportSecurity.TLS)
                : Optional.empty();
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
        else if (record instanceof com.samsung.sure.partner.observability.core.model.CallbackRequestRecord value) result = value.payload();
        else if (record instanceof com.samsung.sure.partner.observability.core.model.CallbackResponseRecord value) result = value.payload();
        if (result == null || result.payload().isEmpty()) return 0;
        return result.payload().get().jsonUtf8Bytes();
    }

    private TelemetryPriority priority(TelemetryEnvelope<?> envelope) {
        return envelope.outcome() == Outcome.SUCCESS || envelope.outcome() == Outcome.UNKNOWN
                ? TelemetryPriority.NORMAL : TelemetryPriority.HIGH;
    }
}
