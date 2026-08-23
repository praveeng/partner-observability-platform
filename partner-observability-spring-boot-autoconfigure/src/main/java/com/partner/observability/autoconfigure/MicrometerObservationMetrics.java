package com.partner.observability.autoconfigure;

import com.partner.observability.core.dispatch.TelemetryPriority;
import com.partner.observability.core.model.AcknowledgementOutcome;
import com.partner.observability.core.model.DeliveryClassification;
import com.partner.observability.core.model.ExchangeMode;
import com.partner.observability.core.model.HttpResult;
import com.partner.observability.core.model.Outcome;
import com.partner.observability.core.model.ProcessingMode;
import com.partner.observability.core.model.ProcessingPhase;
import com.partner.observability.core.model.StatusClass;
import com.partner.observability.core.model.TelemetryRecordType;
import com.partner.observability.core.model.TransportFailureClass;
import com.partner.observability.core.model.TransportOutcome;
import com.partner.observability.core.policy.PayloadCaptureMode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A startup-fixed Micrometer meter manifest. Runtime observations only update an
 * existing meter; they cannot introduce a partner, API, result, or other tag value.
 */
final class MicrometerObservationMetrics implements ObservationMetrics {
    static final int MAX_ACTIVE_SERIES = 10_000;
    private static final int PROMETHEUS_SERIES_PER_TIMER = 13;
    private static final Duration[] BUCKETS = {
        Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250),
        Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2),
        Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(30)
    };
    private static final List<Terminal> HTTP_TERMINALS = List.of(
            new Terminal(Outcome.UNKNOWN, StatusClass.ONE_XX, HttpResult.HTTP_1XX),
            new Terminal(Outcome.SUCCESS, StatusClass.TWO_XX, HttpResult.HTTP_2XX),
            new Terminal(Outcome.SUCCESS, StatusClass.THREE_XX, HttpResult.HTTP_3XX),
            new Terminal(Outcome.BUSINESS_REJECTED, StatusClass.FOUR_XX, HttpResult.HTTP_4XX),
            new Terminal(Outcome.TECHNICAL_FAILURE, StatusClass.FIVE_XX, HttpResult.HTTP_5XX),
            new Terminal(Outcome.TECHNICAL_FAILURE, StatusClass.IO_ERROR, HttpResult.TIMEOUT),
            new Terminal(Outcome.TECHNICAL_FAILURE, StatusClass.IO_ERROR, HttpResult.CONNECTION_FAILURE),
            new Terminal(Outcome.CANCELLED, StatusClass.CANCELLED, HttpResult.CANCELLED),
            new Terminal(Outcome.UNKNOWN, StatusClass.UNKNOWN, HttpResult.UNKNOWN));
    private static final List<AckTerminal> ACK_TERMINALS = List.of(
            new AckTerminal(AcknowledgementOutcome.ACCEPTED, StatusClass.TWO_XX),
            new AckTerminal(AcknowledgementOutcome.REJECTED, StatusClass.ONE_XX),
            new AckTerminal(AcknowledgementOutcome.REJECTED, StatusClass.THREE_XX),
            new AckTerminal(AcknowledgementOutcome.REJECTED, StatusClass.FOUR_XX),
            new AckTerminal(AcknowledgementOutcome.REJECTED, StatusClass.FIVE_XX),
            new AckTerminal(AcknowledgementOutcome.REJECTED, StatusClass.UNKNOWN),
            new AckTerminal(AcknowledgementOutcome.NO_ACK_TIMEOUT, StatusClass.IO_ERROR),
            new AckTerminal(AcknowledgementOutcome.TRANSPORT_FAILURE, StatusClass.IO_ERROR),
            new AckTerminal(AcknowledgementOutcome.CANCELLED, StatusClass.CANCELLED));

    private final String service;
    private final Map<String, Counter> captureAttempts = new HashMap<>();
    private final Map<String, Counter> recordsEnqueued = new HashMap<>();
    private final Map<String, Counter> httpInteractions = new HashMap<>();
    private final Map<String, Timer> httpDurations = new HashMap<>();
    private final Map<String, AtomicInteger> httpInFlight = new HashMap<>();
    private final Map<String, Counter> outboundRetries = new HashMap<>();
    private final Map<String, Counter> asyncAcknowledgements = new HashMap<>();
    private final Map<String, Timer> asyncAcknowledgementDurations = new HashMap<>();
    private final Map<String, Counter> callbackDeliveries = new HashMap<>();
    private final Map<String, Counter> callbackProcessing = new HashMap<>();
    private final Map<String, Timer> callbackProcessingDurations = new HashMap<>();
    private final Map<String, Counter> callbackResponses = new HashMap<>();
    private final Map<String, Counter> callbackDenied = new HashMap<>();
    private final Map<String, Counter> transportFailures = new HashMap<>();
    private final int activeSeries;

    MicrometerObservationMetrics(
            MeterRegistry meterRegistry, String service, ConfiguredObservationRegistry observations) {
        this.service = service;
        registerHealthMeters(meterRegistry);
        observations.outboundDefinitions().forEach(definition -> registerOutbound(meterRegistry, definition));
        observations.callbackDefinitions().forEach(definition -> registerCallback(meterRegistry, definition));
        activeSeries = counterCount() + httpInFlight.size() + timerCount() * PROMETHEUS_SERIES_PER_TIMER;
        if (activeSeries > MAX_ACTIVE_SERIES) {
            throw new IllegalStateException("partner-observability: configured metrics require "
                    + activeSeries + " active Prometheus series; maximum is " + MAX_ACTIVE_SERIES);
        }
    }

    int activeSeries() {
        return activeSeries;
    }

    @Override
    public void submitted(
            ObservationDefinition definition,
            TelemetryRecordType type,
            boolean accepted,
            TelemetryPriority priority) {
        increment(captureAttempts.get(key(value(definition.captureMode()), type.wireValue())));
        if (accepted) {
            increment(recordsEnqueued.get(key(type.wireValue(), value(priority))));
        }
    }

    @Override
    public void outboundStarted(ObservationDefinition definition, int attempt) {
        incrementGauge(httpInFlight.get(base(definition)));
        if (attempt > 1) increment(outboundRetries.get(base(definition)));
    }

    @Override
    public void outboundCompleted(
            ObservationDefinition definition,
            Outcome outcome,
            StatusClass status,
            long durationMs,
            AcknowledgementOutcome acknowledgement,
            HttpResult result) {
        String base = base(definition);
        try {
            increment(httpInteractions.get(key(base, value(outcome), value(status), value(result))));
            record(httpDurations.get(key(base, value(outcome))), durationMs);
            if (acknowledgement != null) {
                increment(asyncAcknowledgements.get(key(base, value(acknowledgement), value(status))));
                record(asyncAcknowledgementDurations.get(key(base, value(acknowledgement))), durationMs);
            }
        } finally {
            decrementGauge(httpInFlight.get(base));
        }
    }

    @Override
    public void callbackReceived(ObservationDefinition definition, DeliveryClassification classification) {
        increment(callbackDeliveries.get(key(base(definition), value(classification))));
    }

    @Override
    public void callbackProcessed(
            ObservationDefinition definition,
            Outcome outcome,
            long durationMs,
            ProcessingMode mode,
            ProcessingPhase phase) {
        String base = base(definition);
        increment(callbackProcessing.get(key(base, value(mode), value(phase), value(outcome))));
        record(callbackProcessingDurations.get(key(base, value(mode), value(outcome))), durationMs);
    }

    @Override
    public void callbackResponded(
            ObservationDefinition definition,
            Outcome outcome,
            StatusClass status,
            TransportOutcome transport) {
        String base = base(definition);
        try {
            increment(callbackResponses.get(key(base, value(outcome), value(status), value(transport))));
        } finally {
            decrementGauge(httpInFlight.get(base));
        }
    }

    @Override
    public void callbackDenied(String reason) {
        increment(callbackDenied.get(normalizedDeniedReason(reason)));
    }

    @Override
    public void transportSecurityFailure(
            ObservationDefinition definition, TransportFailureClass failureClass) {
        increment(transportFailures.get(key(base(definition), value(failureClass))));
    }

    @Override
    public void callbackStarted(ObservationDefinition definition) {
        incrementGauge(httpInFlight.get(base(definition)));
    }

    private void registerHealthMeters(MeterRegistry registry) {
        for (PayloadCaptureMode mode : PayloadCaptureMode.values()) {
            for (TelemetryRecordType type : TelemetryRecordType.values()) {
                String key = key(value(mode), type.wireValue());
                captureAttempts.put(key, Counter.builder("partner_observability_capture_attempts_total")
                        .tags("service", service, "capture_mode", value(mode), "record_type", type.wireValue())
                        .register(registry));
            }
        }
        for (TelemetryRecordType type : TelemetryRecordType.values()) {
            for (TelemetryPriority priority : TelemetryPriority.values()) {
                String key = key(type.wireValue(), value(priority));
                recordsEnqueued.put(key, Counter.builder("partner_observability_records_enqueued_total")
                        .tags("service", service, "record_type", type.wireValue(), "queue", value(priority))
                        .register(registry));
            }
        }
        for (String reason : List.of("untrusted", "conflict", "unknown")) {
            callbackDenied.put(reason, Counter.builder("partner_observability_callback_ingress_denied_total")
                    .tags("service", service, "reason", reason).register(registry));
        }
    }

    private void registerOutbound(MeterRegistry registry, ObservationDefinition definition) {
        String base = base(definition);
        registerHttpMeters(registry, definition);
        outboundRetries.put(base, Counter.builder("partner_observability_outbound_retries_total")
                .tags(baseTags(definition)).register(registry));
        if (definition.exchangeMode() == ExchangeMode.ASYNC_INITIATION) {
            for (AckTerminal terminal : ACK_TERMINALS) {
                asyncAcknowledgements.put(
                        key(base, value(terminal.acknowledgement()), value(terminal.status())),
                        Counter.builder("partner_observability_async_acknowledgements_total")
                                .tags("service", service,
                                        "partner_slot", definition.partnerContext().partnerSlot(),
                                        "api", definition.name(),
                                        "ack_outcome", value(terminal.acknowledgement()),
                                        "status_class", value(terminal.status()))
                                .register(registry));
                asyncAcknowledgementDurations.computeIfAbsent(
                        key(base, value(terminal.acknowledgement())), ignored -> timer(
                                registry, "partner_observability_async_acknowledgement_duration_seconds",
                                "service", service,
                                "partner_slot", definition.partnerContext().partnerSlot(),
                                "api", definition.name(),
                                "ack_outcome", value(terminal.acknowledgement())));
            }
        }
        for (TransportFailureClass failure : TransportFailureClass.values()) {
            transportFailures.put(key(base, value(failure)), Counter.builder(
                            "partner_observability_transport_security_failures_total")
                    .tags("service", service, "api", definition.name(), "direction", "outbound",
                            "interaction_kind", interactionKind(definition),
                            "transport_failure_class", value(failure))
                    .register(registry));
        }
    }

    private void registerCallback(MeterRegistry registry, ObservationDefinition definition) {
        String base = base(definition);
        registerHttpInFlight(registry, definition);
        for (DeliveryClassification classification : DeliveryClassification.values()) {
            callbackDeliveries.put(key(base, value(classification)), Counter.builder(
                            "partner_observability_callback_deliveries_total")
                    .tags("service", service, "partner_slot", definition.partnerContext().partnerSlot(),
                            "api", definition.name(), "delivery_class", value(classification))
                    .register(registry));
        }
        for (ProcessingMode mode : ProcessingMode.values()) {
            registerProcessing(registry, definition, mode, ProcessingPhase.BUSINESS_PROCESSING, Outcome.SUCCESS);
            for (ProcessingPhase phase : ProcessingPhase.values()) {
                registerProcessing(registry, definition, mode, phase, Outcome.TECHNICAL_FAILURE);
            }
        }
        for (StatusClass status : StatusClass.values()) {
            registerCallbackResponse(registry, definition, outcome(status), status, TransportOutcome.WRITE_COMPLETED);
            registerCallbackResponse(
                    registry, definition, Outcome.TECHNICAL_FAILURE, status, TransportOutcome.WRITE_FAILED);
            registerCallbackResponse(
                    registry, definition, Outcome.TECHNICAL_FAILURE, status, TransportOutcome.UNKNOWN);
        }
        registerCallbackResponse(
                registry, definition, Outcome.CANCELLED, StatusClass.CANCELLED, TransportOutcome.CANCELLED);
    }

    private void registerHttpMeters(MeterRegistry registry, ObservationDefinition definition) {
        registerHttpInFlight(registry, definition);
        String base = base(definition);
        for (Terminal terminal : HTTP_TERMINALS) {
            httpInteractions.put(
                    key(base, value(terminal.outcome()), value(terminal.status()), value(terminal.result())),
                    Counter.builder("partner_observability_http_interactions_total")
                            .tags(baseTags(definition))
                            .tags("outcome", value(terminal.outcome()), "status_class", value(terminal.status()),
                                    "result", value(terminal.result()))
                            .register(registry));
            httpDurations.computeIfAbsent(key(base, value(terminal.outcome())), ignored -> timer(
                    registry, "partner_observability_http_duration_seconds",
                    append(baseTags(definition), "outcome", value(terminal.outcome()))));
        }
    }

    private void registerHttpInFlight(MeterRegistry registry, ObservationDefinition definition) {
        String base = base(definition);
        AtomicInteger value = new AtomicInteger();
        httpInFlight.put(base, value);
        Gauge.builder("partner_observability_http_in_flight", value, AtomicInteger::get)
                .tags(baseTags(definition)).register(registry);
    }

    private void registerProcessing(
            MeterRegistry registry,
            ObservationDefinition definition,
            ProcessingMode mode,
            ProcessingPhase phase,
            Outcome outcome) {
        String base = base(definition);
        callbackProcessing.put(key(base, value(mode), value(phase), value(outcome)), Counter.builder(
                        "partner_observability_callback_processing_total")
                .tags("service", service, "partner_slot", definition.partnerContext().partnerSlot(),
                        "api", definition.name(), "processing_mode", value(mode),
                        "processing_phase", value(phase), "outcome", value(outcome))
                .register(registry));
        callbackProcessingDurations.computeIfAbsent(key(base, value(mode), value(outcome)), ignored -> timer(
                registry, "partner_observability_callback_processing_duration_seconds",
                "service", service, "partner_slot", definition.partnerContext().partnerSlot(),
                "api", definition.name(), "processing_mode", value(mode), "outcome", value(outcome)));
    }

    private void registerCallbackResponse(
            MeterRegistry registry,
            ObservationDefinition definition,
            Outcome outcome,
            StatusClass status,
            TransportOutcome transport) {
        String key = key(base(definition), value(outcome), value(status), value(transport));
        callbackResponses.computeIfAbsent(key, ignored -> Counter.builder(
                        "partner_observability_callback_response_total")
                .tags("service", service, "partner_slot", definition.partnerContext().partnerSlot(),
                        "api", definition.name(), "outcome", value(outcome),
                        "status_class", value(status), "result", value(transport))
                .register(registry));
    }

    private Timer timer(MeterRegistry registry, String name, String... tags) {
        return Timer.builder(name)
                .tags(tags)
                .serviceLevelObjectives(BUCKETS)
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(30))
                .register(registry);
    }

    private int counterCount() {
        return captureAttempts.size() + recordsEnqueued.size() + httpInteractions.size()
                + outboundRetries.size() + asyncAcknowledgements.size() + callbackDeliveries.size()
                + callbackProcessing.size() + callbackResponses.size() + callbackDenied.size()
                + transportFailures.size();
    }

    private int timerCount() {
        return httpDurations.size() + asyncAcknowledgementDurations.size()
                + callbackProcessingDurations.size();
    }

    private String base(ObservationDefinition definition) {
        return key(definition.partnerContext().partnerSlot(), definition.name(), interactionKind(definition));
    }

    private String[] baseTags(ObservationDefinition definition) {
        return new String[] {
            "service", service,
            "partner_slot", definition.partnerContext().partnerSlot(),
            "api", definition.name(),
            "interaction_kind", interactionKind(definition),
            "direction", definition.callback() ? "inbound" : "outbound"
        };
    }

    private String interactionKind(ObservationDefinition definition) {
        if (definition.callback()) return "callback";
        return definition.exchangeMode() == ExchangeMode.SYNC ? "sync_outbound" : "async_initiation";
    }

    private static String[] append(String[] source, String... tail) {
        String[] result = new String[source.length + tail.length];
        System.arraycopy(source, 0, result, 0, source.length);
        System.arraycopy(tail, 0, result, source.length, tail.length);
        return result;
    }

    private static void increment(Counter counter) {
        if (counter == null) return;
        try {
            counter.increment();
        } catch (RuntimeException ignored) {
            // Metrics cannot affect business traffic.
        }
    }

    private static void record(Timer timer, long durationMs) {
        if (timer == null) return;
        try {
            timer.record(Duration.ofMillis(Math.max(0, durationMs)));
        } catch (RuntimeException ignored) {
            // Metrics cannot affect business traffic.
        }
    }

    private static void incrementGauge(AtomicInteger gauge) {
        if (gauge == null) return;
        try {
            gauge.incrementAndGet();
        } catch (RuntimeException ignored) {
            // Metrics cannot affect business traffic.
        }
    }

    private static void decrementGauge(AtomicInteger gauge) {
        if (gauge == null) return;
        try {
            gauge.updateAndGet(current -> Math.max(0, current - 1));
        } catch (RuntimeException ignored) {
            // Metrics cannot affect business traffic.
        }
    }

    private static String normalizedDeniedReason(String reason) {
        return "untrusted".equals(reason) || "conflict".equals(reason) ? reason : "unknown";
    }

    private static Outcome outcome(StatusClass status) {
        return switch (status) {
            case TWO_XX, THREE_XX -> Outcome.SUCCESS;
            case FOUR_XX -> Outcome.BUSINESS_REJECTED;
            case FIVE_XX, IO_ERROR -> Outcome.TECHNICAL_FAILURE;
            case CANCELLED -> Outcome.CANCELLED;
            default -> Outcome.UNKNOWN;
        };
    }

    private static String value(Enum<?> value) {
        if (value instanceof StatusClass status) {
            return switch (status) {
                case ONE_XX -> "1xx";
                case TWO_XX -> "2xx";
                case THREE_XX -> "3xx";
                case FOUR_XX -> "4xx";
                case FIVE_XX -> "5xx";
                case IO_ERROR -> "io_error";
                case CANCELLED -> "cancelled";
                case UNKNOWN -> "unknown";
            };
        }
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static String key(String... values) {
        return String.join("\u001f", values);
    }

    private record Terminal(Outcome outcome, StatusClass status, HttpResult result) {}
    private record AckTerminal(AcknowledgementOutcome acknowledgement, StatusClass status) {}
}
