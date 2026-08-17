package com.partner.observability.autoconfigure;

import com.partner.observability.core.model.CallbackProcessingEventRecord;
import com.partner.observability.core.model.CallbackRequestRecord;
import com.partner.observability.core.model.CallbackResponseRecord;
import com.partner.observability.core.model.CorrelationIdentifiers;
import com.partner.observability.core.model.DeliveryClassification;
import com.partner.observability.core.model.Direction;
import com.partner.observability.core.model.InteractionContext;
import com.partner.observability.core.model.InteractionKind;
import com.partner.observability.core.model.Outcome;
import com.partner.observability.core.model.ParsingStatus;
import com.partner.observability.core.model.PartnerHttpMethod;
import com.partner.observability.core.model.ProcessingMode;
import com.partner.observability.core.model.ProcessingPhase;
import com.partner.observability.core.model.StatusClass;
import com.partner.observability.core.model.TimelineStage;
import com.partner.observability.core.model.TransportOutcome;
import com.partner.observability.core.payload.PayloadStatus;
import com.partner.observability.core.payload.SanitizationResult;
import com.partner.observability.core.policy.PayloadCaptureMode;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One callback delivery observation. It contains only configured identity and already-safe values,
 * so a host may carry it to bounded background work without retaining the servlet request/body.
 */
public final class CallbackObservation {

    private static final SanitizationResult NO_VALUES = SanitizationResult.omitted(PayloadStatus.NOT_REQUESTED);

    private final PartnerObservationEngine engine;
    private final ObservationDefinition definition;
    private final Instant receivedAt;
    private final PartnerHttpMethod method;
    private final String contentType;
    private final OptionalLong declaredSize;
    private final UUID interactionId;
    private final UUID attemptId;
    private final long ingressNanos;
    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicBoolean received = new AtomicBoolean();
    private final AtomicBoolean authenticated = new AtomicBoolean();
    private final AtomicBoolean validated = new AtomicBoolean();
    private final AtomicBoolean processingStarted = new AtomicBoolean();
    private final AtomicBoolean processingTerminal = new AtomicBoolean();
    private final AtomicBoolean responseTerminal = new AtomicBoolean();
    private volatile CorrelationIdentifiers identifiers = CorrelationIdentifiers.empty();
    private volatile long processingStartedNanos;
    private volatile SanitizationResult responsePayload = NO_VALUES;
    private volatile PayloadStatus responsePayloadStatus = PayloadStatus.NOT_REQUESTED;

    CallbackObservation(
            PartnerObservationEngine engine,
            ObservationDefinition definition,
            Instant receivedAt,
            PartnerHttpMethod method,
            String contentType,
            OptionalLong declaredSize,
            UUID interactionId,
            UUID attemptId,
            long ingressNanos) {
        this.engine = engine;
        this.definition = definition;
        this.receivedAt = receivedAt;
        this.method = method;
        this.contentType = contentType;
        this.declaredSize = declaredSize;
        this.interactionId = interactionId;
        this.attemptId = attemptId;
        this.ingressNanos = ingressNanos;
    }

    public UUID interactionId() { return interactionId; }
    public UUID callbackAttemptId() { return attemptId; }

    public void received(
            Object decodedBody,
            CorrelationIdentifiers explicitIdentifiers,
            DeliveryClassification classification,
            ParsingStatus parsingStatus) {
        if (!received.compareAndSet(false, true)) {
            return;
        }
        try {
            PayloadCaptureMode mode = engine.effectiveMode(definition);
            CapturedBody captured = engine.capture(
                    definition, ObservationLeg.CALLBACK_REQUEST, decodedBody, decodedBody != null,
                    contentType, declaredSize, mode);
            identifiers = (explicitIdentifiers == null ? CorrelationIdentifiers.empty() : explicitIdentifiers)
                    .merge(captured.identifiers());
            TimelineStage stage = classification == DeliveryClassification.RETRY
                            || classification == DeliveryClassification.DUPLICATE
                    ? TimelineStage.CALLBACK_RETRY_RECEIVED
                    : TimelineStage.CALLBACK_RECEIVED;
            InteractionContext interaction = interaction(stage);
            CallbackRequestRecord record = new CallbackRequestRecord(
                    definition.name(), definition.path(), method, classification,
                    PartnerObservationEngine.normalizedContentType(contentType), declaredSize,
                    NO_VALUES, PartnerObservationEngine.safeResult(captured.payload()), parsingStatus, receivedAt);
            engine.submit(definition, engine.envelope(
                    definition, receivedAt, interaction, mode, captured.payload().status(), Outcome.UNKNOWN, record));
            engine.metrics().callbackReceived(definition, classification);
        } catch (RuntimeException ignored) {
            // Callback behavior is independent of observation.
        }
    }

    public void receivedMetadataOnly() {
        received(null, CorrelationIdentifiers.empty(), DeliveryClassification.UNKNOWN, ParsingStatus.NOT_ATTEMPTED);
    }

    public void authenticated() {
        receivedMetadataOnly();
        if (authenticated.compareAndSet(false, true)) {
            processingEvent(
                    TimelineStage.CALLBACK_AUTHENTICATED, ProcessingPhase.AUTHENTICATION,
                    ProcessingMode.INLINE, Outcome.SUCCESS, null, OptionalLong.empty(), Optional.empty());
        }
    }

    public void validated() {
        authenticated();
        if (validated.compareAndSet(false, true)) {
            processingEvent(
                    TimelineStage.CALLBACK_VALIDATED, ProcessingPhase.VALIDATION,
                    ProcessingMode.INLINE, Outcome.SUCCESS, null, OptionalLong.empty(), Optional.empty());
        }
    }

    public void processingStarted(ProcessingMode mode) {
        receivedMetadataOnly();
        if (processingStarted.compareAndSet(false, true)) {
            processingStartedNanos = System.nanoTime();
            processingEvent(
                    TimelineStage.CALLBACK_PROCESSING_STARTED, ProcessingPhase.BUSINESS_PROCESSING,
                    mode, Outcome.UNKNOWN, null, OptionalLong.empty(), Optional.empty());
        }
    }

    public void processingSucceeded(ProcessingMode mode, boolean acceptedBeforeCompletion) {
        processingStarted(mode);
        if (processingTerminal.compareAndSet(false, true)) {
            long duration = processingDuration();
            processingEvent(
                    TimelineStage.CALLBACK_PROCESSED, ProcessingPhase.BUSINESS_PROCESSING,
                    mode, Outcome.SUCCESS, null, OptionalLong.of(duration), Optional.of(acceptedBeforeCompletion));
            engine.metrics().callbackProcessed(
                    definition, Outcome.SUCCESS, duration, mode, ProcessingPhase.BUSINESS_PROCESSING);
        }
    }

    public void processingFailed(
            ProcessingMode mode, ProcessingPhase phase, String configuredErrorCode, boolean acceptedBeforeCompletion) {
        if (phase == ProcessingPhase.BUSINESS_PROCESSING || phase == ProcessingPhase.DOWNSTREAM_PROCESSING) {
            processingStarted(mode);
        } else {
            receivedMetadataOnly();
        }
        if (processingTerminal.compareAndSet(false, true)) {
            long duration = processingStarted.get() ? processingDuration() : 0;
            processingEvent(
                    TimelineStage.CALLBACK_PROCESSING_FAILED, phase, mode, Outcome.TECHNICAL_FAILURE,
                    configuredErrorCode, processingStarted.get() ? OptionalLong.of(duration) : OptionalLong.empty(),
                    Optional.of(acceptedBeforeCompletion));
            engine.metrics().callbackProcessed(definition, Outcome.TECHNICAL_FAILURE, duration, mode, phase);
        }
    }

    /** Sanitizes immediately; no response DTO/reference is retained until transport completion. */
    public void captureResponse(Object decodedBody) {
        try {
            PayloadCaptureMode mode = engine.effectiveMode(definition);
            CapturedBody captured = engine.capture(
                    definition, ObservationLeg.CALLBACK_RESPONSE, decodedBody, decodedBody != null,
                    "application/json", OptionalLong.empty(), mode);
            responsePayload = PartnerObservationEngine.safeResult(captured.payload());
            responsePayloadStatus = captured.payload().status();
            identifiers = identifiers.merge(captured.identifiers());
        } catch (RuntimeException ignored) {
            responsePayload = NO_VALUES;
            responsePayloadStatus = PayloadStatus.MALFORMED;
        }
    }

    public void response(int status, TransportOutcome transportOutcome) {
        receivedMetadataOnly();
        if (!responseTerminal.compareAndSet(false, true)) {
            return;
        }
        try {
            PayloadCaptureMode mode = engine.effectiveMode(definition);
            StatusClass statusClass = transportOutcome == TransportOutcome.CANCELLED
                    ? StatusClass.CANCELLED : PartnerObservationEngine.statusClass(status);
            Outcome outcome = transportOutcome == TransportOutcome.WRITE_COMPLETED
                    ? PartnerObservationEngine.outcome(statusClass)
                    : transportOutcome == TransportOutcome.CANCELLED
                            ? Outcome.CANCELLED : Outcome.TECHNICAL_FAILURE;
            TimelineStage stage = transportOutcome == TransportOutcome.WRITE_COMPLETED
                    ? TimelineStage.CALLBACK_RESPONSE_SENT
                    : TimelineStage.CALLBACK_RESPONSE_WRITE_FAILED;
            long duration = Math.max(0, (System.nanoTime() - ingressNanos) / 1_000_000L);
            CallbackResponseRecord record = new CallbackResponseRecord(
                    definition.name(), OptionalInt.of(status), statusClass, outcome, duration,
                    transportOutcome, Optional.empty(), Optional.empty(), OptionalLong.empty(),
                    NO_VALUES, responsePayload);
            engine.submit(definition, engine.envelope(
                    definition, Instant.now(), interaction(stage), mode, responsePayloadStatus, outcome, record));
            engine.metrics().callbackResponded(definition, outcome, statusClass, transportOutcome);
        } catch (RuntimeException ignored) {
            // Callback response is already owned by the host framework.
        }
    }

    private void processingEvent(
            TimelineStage stage,
            ProcessingPhase phase,
            ProcessingMode mode,
            Outcome outcome,
            String errorCode,
            OptionalLong duration,
            Optional<Boolean> acceptedBeforeCompletion) {
        if (!definition.processingEventsEnabled()) {
            return;
        }
        try {
            PayloadCaptureMode captureMode = engine.effectiveMode(definition)
                    .reduceTo(PayloadCaptureMode.METADATA_ONLY);
            CallbackProcessingEventRecord record = new CallbackProcessingEventRecord(
                    definition.name(), mode, phase, outcome, Optional.ofNullable(errorCode),
                    duration, acceptedBeforeCompletion, NO_VALUES);
            engine.submit(definition, engine.envelope(
                    definition, Instant.now(), interaction(stage), captureMode,
                    PayloadStatus.NOT_REQUESTED, outcome, record));
        } catch (RuntimeException ignored) {
            // Semantic facts are best effort and cannot affect business processing.
        }
    }

    private InteractionContext interaction(TimelineStage stage) {
        return new InteractionContext(
                InteractionKind.CALLBACK, Direction.INBOUND_FROM_PARTNER, interactionId,
                sequence.getAndIncrement(), Optional.of(attemptId), definition.correlationProfile(),
                identifiers, Optional.of(stage));
    }

    private long processingDuration() {
        return Math.max(0, (System.nanoTime() - processingStartedNanos) / 1_000_000L);
    }
}
