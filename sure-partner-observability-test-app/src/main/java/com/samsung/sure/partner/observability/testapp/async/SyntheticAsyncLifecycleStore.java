package com.samsung.sure.partner.observability.testapp.async;

import com.samsung.sure.partner.observability.testapp.model.SyntheticAsyncAcknowledgement;
import com.samsung.sure.partner.observability.testapp.model.SyntheticAsyncJourneySnapshot;
import com.samsung.sure.partner.observability.testapp.model.SyntheticAsyncRequest;
import com.samsung.sure.partner.observability.testapp.model.SyntheticAsyncScenario;
import com.samsung.sure.partner.observability.testapp.model.SyntheticCallbackAttempt;
import com.samsung.sure.partner.observability.testapp.model.SyntheticCallbackDelivery;
import com.samsung.sure.partner.observability.testapp.model.SyntheticCorrelationIdentifiers;
import com.samsung.sure.partner.observability.testapp.model.SyntheticLifecycleEvent;
import com.samsung.sure.partner.observability.testapp.model.SyntheticLifecycleStage;
import com.samsung.sure.partner.observability.testapp.model.SyntheticPartner;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/**
 * Bounded fixture-only journey ledger. It retains identifiers and outcomes, never callback payloads
 * or headers, and is not a production correlation store.
 */
@Component
public final class SyntheticAsyncLifecycleStore {

    // Covers the approved 500 callbacks/s fixture with two-second deferred completion and headroom.
    private static final int MAX_JOURNEYS = 4096;
    private static final int MAX_EVENTS_PER_JOURNEY = 256;
    private static final int MAX_CALLBACKS_PER_JOURNEY = 128;
    private static final int MAX_DELIVERIES_PER_JOURNEY = 128;

    private final Map<String, MutableJourney> journeys = new LinkedHashMap<>();
    private final LongAdder journeysBegun = new LongAdder();
    private final LongAdder acknowledgementsReceived = new LongAdder();
    private final LongAdder callbacksReceived = new LongAdder();
    private final LongAdder callbacksProcessed = new LongAdder();
    private final LongAdder callbackProcessingFailures = new LongAdder();
    private final LongAdder callbackResponsesSent = new LongAdder();
    private final LongAdder callbackResponseWriteFailures = new LongAdder();
    private final LongAdder callbackResponses200 = new LongAdder();
    private final LongAdder callbackResponses202 = new LongAdder();
    private final LongAdder callbackResponses4xx = new LongAdder();
    private final LongAdder callbackResponses5xx = new LongAdder();

    public synchronized void begin(SyntheticAsyncRequest request) {
        Objects.requireNonNull(request, "request");
        if (journeys.containsKey(request.runId())) {
            throw new IllegalArgumentException("SYNTHETIC_RUN_ALREADY_EXISTS");
        }
        if (journeys.size() == MAX_JOURNEYS) {
            String eldest = journeys.keySet().iterator().next();
            journeys.remove(eldest);
        }
        MutableJourney journey = new MutableJourney(request);
        journey.event(SyntheticLifecycleStage.ASYNC_REQUEST_SENT, null, "DELEGATED", null);
        journeys.put(request.runId(), journey);
        journeysBegun.increment();
    }

    public void acknowledgement(
            String runId, int httpStatus, SyntheticAsyncAcknowledgement acknowledgement) {
        MutableJourney journey = required(runId);
        synchronized (journey) {
            journey.acknowledgementHttpStatus = httpStatus;
            journey.acknowledgementReceived = true;
            journey.identifiers = journey.identifiers.merge(acknowledgement.correlationBridge());
            journey.event(
                    SyntheticLifecycleStage.ASYNC_ACK_RECEIVED, null, acknowledgement.acknowledgement(), httpStatus);
            acknowledgementsReceived.increment();
        }
    }

    public void acknowledgementNotReceived(String runId) {
        MutableJourney journey = required(runId);
        synchronized (journey) {
            journey.acknowledgementHttpStatus = 0;
            journey.acknowledgementReceived = false;
            journey.event(SyntheticLifecycleStage.ASYNC_ACK_NOT_RECEIVED, null, "TIMEOUT", null);
        }
    }

    public Optional<SyntheticAsyncScenario> authorizedScenario(String runId, SyntheticPartner partner) {
        MutableJourney journey = find(runId);
        if (journey == null || journey.partner != partner) {
            return Optional.empty();
        }
        return Optional.of(journey.scenario);
    }

    public CallbackHandle callbackReceived(
            String runId,
            SyntheticPartner authenticatedPartner,
            SyntheticCorrelationIdentifiers identifiers,
            Integer callbackSequence,
            int requestBytes,
            String payloadCategory) {
        MutableJourney journey = required(runId);
        synchronized (journey) {
            if (journey.partner != authenticatedPartner) {
                throw new IllegalArgumentException("SYNTHETIC_CALLBACK_PARTNER_CONFLICT");
            }
            if (journey.callbacks.size() == MAX_CALLBACKS_PER_JOURNEY) {
                throw new IllegalStateException("SYNTHETIC_CALLBACK_LIMIT_REACHED");
            }
            String classification = classification(journey);
            String attemptId = UUID.randomUUID().toString();
            MutableCallback callback = new MutableCallback(
                    attemptId,
                    classification,
                    identifiers,
                    callbackSequence,
                    requestBytes,
                    payloadCategory);
            journey.callbacks.add(callback);
            SyntheticLifecycleStage receipt = "INITIAL".equals(classification)
                    ? SyntheticLifecycleStage.CALLBACK_RECEIVED
                    : SyntheticLifecycleStage.CALLBACK_RETRY_RECEIVED;
            journey.event(receipt, attemptId, classification, null);
            journey.event(SyntheticLifecycleStage.CALLBACK_AUTHENTICATED, attemptId, "SUCCESS", null);
            callbacksReceived.increment();
            return new CallbackHandle(runId, attemptId, journey.scenario, journey.callbacks.size());
        }
    }

    public void processingStarted(CallbackHandle handle) {
        update(handle, (journey, callback) -> {
            callback.processingOutcome = "STARTED";
            journey.event(
                    SyntheticLifecycleStage.CALLBACK_PROCESSING_STARTED,
                    handle.callbackAttemptId(),
                    "STARTED",
                    null);
        });
    }

    public void processingSucceeded(CallbackHandle handle) {
        update(handle, (journey, callback) -> {
            callback.processingOutcome = "SUCCESS";
            journey.event(
                    SyntheticLifecycleStage.CALLBACK_PROCESSED,
                    handle.callbackAttemptId(),
                    "SUCCESS",
                    null);
            callbacksProcessed.increment();
        });
    }

    public void processingFailed(CallbackHandle handle, String outcome) {
        update(handle, (journey, callback) -> {
            callback.processingOutcome = outcome;
            journey.event(
                    SyntheticLifecycleStage.CALLBACK_PROCESSING_FAILED,
                    handle.callbackAttemptId(),
                    outcome,
                    null);
            callbackProcessingFailures.increment();
        });
    }

    public void responseSent(CallbackHandle handle, int status) {
        update(handle, (journey, callback) -> {
            callback.responseStatus = status;
            callback.responseTransportOutcome = "WRITE_COMPLETED";
            journey.event(
                    SyntheticLifecycleStage.CALLBACK_RESPONSE_SENT,
                    handle.callbackAttemptId(),
                    "WRITE_COMPLETED",
                    status);
            callbackResponsesSent.increment();
            if (status == 200) callbackResponses200.increment();
            else if (status == 202) callbackResponses202.increment();
            else if (status >= 400 && status < 500) callbackResponses4xx.increment();
            else if (status >= 500) callbackResponses5xx.increment();
        });
    }

    public void responseWriteFailed(CallbackHandle handle, int status) {
        update(handle, (journey, callback) -> {
            callback.responseStatus = status;
            callback.responseTransportOutcome = "WRITE_FAILED";
            journey.event(
                    SyntheticLifecycleStage.CALLBACK_RESPONSE_WRITE_FAILED,
                    handle.callbackAttemptId(),
                    "WRITE_FAILED",
                    status);
            callbackResponseWriteFailures.increment();
        });
    }

    /** Returns aggregate fixture-only lifecycle counts without retaining payloads or identifiers. */
    public Map<String, Long> performanceSnapshot() {
        return Map.ofEntries(
                Map.entry("journeysBegun", journeysBegun.sum()),
                Map.entry("acknowledgementsReceived", acknowledgementsReceived.sum()),
                Map.entry("callbacksReceived", callbacksReceived.sum()),
                Map.entry("callbacksProcessed", callbacksProcessed.sum()),
                Map.entry("callbackProcessingFailures", callbackProcessingFailures.sum()),
                Map.entry("callbackResponsesSent", callbackResponsesSent.sum()),
                Map.entry("callbackResponseWriteFailures", callbackResponseWriteFailures.sum()),
                Map.entry("callbackResponses200", callbackResponses200.sum()),
                Map.entry("callbackResponses202", callbackResponses202.sum()),
                Map.entry("callbackResponses4xx", callbackResponses4xx.sum()),
                Map.entry("callbackResponses5xx", callbackResponses5xx.sum()));
    }

    /** Resets bounded test-only state between performance phases. */
    public synchronized void resetPerformanceState() {
        journeys.clear();
        journeysBegun.reset();
        acknowledgementsReceived.reset();
        callbacksReceived.reset();
        callbacksProcessed.reset();
        callbackProcessingFailures.reset();
        callbackResponsesSent.reset();
        callbackResponseWriteFailures.reset();
        callbackResponses200.reset();
        callbackResponses202.reset();
        callbackResponses4xx.reset();
        callbackResponses5xx.reset();
    }

    public void mockDelivery(
            String runId,
            int deliveryNumber,
            SyntheticPartner targetPartner,
            Integer httpStatus,
            String transportOutcome) {
        MutableJourney journey = find(runId);
        if (journey == null) {
            return;
        }
        synchronized (journey) {
            if (journey.deliveries.size() < MAX_DELIVERIES_PER_JOURNEY) {
                journey.deliveries.add(new SyntheticCallbackDelivery(
                        deliveryNumber, targetPartner.name(), httpStatus, transportOutcome));
            }
        }
    }

    public SyntheticAsyncJourneySnapshot snapshot(String runId) {
        MutableJourney journey = required(runId);
        synchronized (journey) {
            List<SyntheticCallbackAttempt> callbacks = journey.callbacks.stream()
                    .map(MutableCallback::snapshot)
                    .toList();
            return new SyntheticAsyncJourneySnapshot(
                    journey.runId,
                    journey.scenario.name(),
                    journey.partner.name(),
                    journey.acknowledgementHttpStatus,
                    journey.acknowledgementReceived,
                    journey.identifiers,
                    List.copyOf(journey.events),
                    callbacks,
                    List.copyOf(journey.deliveries));
        }
    }

    private String classification(MutableJourney journey) {
        if (journey.callbacks.isEmpty()) {
            return "INITIAL";
        }
        return switch (journey.scenario) {
            case CALLBACK_RETRY -> "RETRY";
            case DUPLICATE_CALLBACK -> "DUPLICATE";
            default -> "INITIAL";
        };
    }

    private void update(CallbackHandle handle, CallbackMutation mutation) {
        MutableJourney journey = required(handle.runId());
        synchronized (journey) {
            MutableCallback callback = journey.callbacks.stream()
                    .filter(candidate -> candidate.attemptId.equals(handle.callbackAttemptId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("SYNTHETIC_CALLBACK_ATTEMPT_UNKNOWN"));
            mutation.apply(journey, callback);
        }
    }

    private synchronized MutableJourney find(String runId) {
        return journeys.get(runId);
    }

    private MutableJourney required(String runId) {
        MutableJourney journey = find(runId);
        if (journey == null) {
            throw new IllegalArgumentException("SYNTHETIC_RUN_UNKNOWN");
        }
        return journey;
    }

    public record CallbackHandle(
            String runId,
            String callbackAttemptId,
            SyntheticAsyncScenario scenario,
            int attemptNumber) {}

    @FunctionalInterface
    private interface CallbackMutation {
        void apply(MutableJourney journey, MutableCallback callback);
    }

    private static final class MutableJourney {
        private final String runId;
        private final SyntheticPartner partner;
        private final SyntheticAsyncScenario scenario;
        private final List<SyntheticLifecycleEvent> events = new ArrayList<>();
        private final List<MutableCallback> callbacks = new ArrayList<>();
        private final List<SyntheticCallbackDelivery> deliveries = new ArrayList<>();
        private SyntheticCorrelationIdentifiers identifiers;
        private int acknowledgementHttpStatus;
        private boolean acknowledgementReceived;
        private int eventSequence;

        private MutableJourney(SyntheticAsyncRequest request) {
            runId = request.runId();
            partner = request.partner();
            scenario = request.scenario();
            identifiers = request.identifiers();
        }

        private void event(
                SyntheticLifecycleStage stage,
                String callbackAttemptId,
                String outcome,
                Integer httpStatus) {
            if (events.size() == MAX_EVENTS_PER_JOURNEY) {
                throw new IllegalStateException("SYNTHETIC_EVENT_LIMIT_REACHED");
            }
            events.add(new SyntheticLifecycleEvent(
                    eventSequence++, Instant.now(), stage, callbackAttemptId, outcome, httpStatus));
        }
    }

    private static final class MutableCallback {
        private final String attemptId;
        private final String deliveryClassification;
        private final SyntheticCorrelationIdentifiers identifiers;
        private final Integer callbackSequence;
        private final int requestBytes;
        private final String payloadCategory;
        private String processingOutcome = "NOT_STARTED";
        private Integer responseStatus;
        private String responseTransportOutcome = "NOT_RECORDED";

        private MutableCallback(
                String attemptId,
                String deliveryClassification,
                SyntheticCorrelationIdentifiers identifiers,
                Integer callbackSequence,
                int requestBytes,
                String payloadCategory) {
            this.attemptId = attemptId;
            this.deliveryClassification = deliveryClassification;
            this.identifiers = identifiers;
            this.callbackSequence = callbackSequence;
            this.requestBytes = requestBytes;
            this.payloadCategory = payloadCategory;
        }

        private SyntheticCallbackAttempt snapshot() {
            return new SyntheticCallbackAttempt(
                    attemptId,
                    deliveryClassification,
                    identifiers,
                    callbackSequence,
                    requestBytes,
                    payloadCategory,
                    processingOutcome,
                    responseStatus,
                    responseTransportOutcome);
        }
    }
}
