package com.partner.observability.autoconfigure;

import com.partner.observability.core.model.AcknowledgementOutcome;
import com.partner.observability.core.model.DeliveryClassification;
import com.partner.observability.core.model.Outcome;
import com.partner.observability.core.model.ProcessingMode;
import com.partner.observability.core.model.ProcessingPhase;
import com.partner.observability.core.model.StatusClass;
import com.partner.observability.core.model.TelemetryRecordType;
import com.partner.observability.core.model.TransportFailureClass;
import com.partner.observability.core.model.TransportOutcome;
import com.partner.observability.core.dispatch.TelemetryPriority;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;

final class MicrometerObservationMetrics implements ObservationMetrics {
    private final MeterRegistry registry;
    private final String service;

    MicrometerObservationMetrics(MeterRegistry registry, String service) {
        this.registry = registry;
        this.service = service;
    }

    @Override
    public void submitted(
            ObservationDefinition definition,
            TelemetryRecordType type,
            boolean accepted,
            TelemetryPriority priority) {
        Counter.builder("partner_observability_capture_attempts_total")
                .tag("service", service)
                .tag("capture_mode", definition.captureMode().name().toLowerCase(Locale.ROOT))
                .tag("record_type", type.wireValue())
                .register(registry)
                .increment();
        if (!accepted) {
            return;
        }
        Counter.builder("partner_observability_records_enqueued_total")
                .tag("service", service)
                .tag("record_type", type.wireValue())
                .tag("queue", priority.name().toLowerCase(Locale.ROOT))
                .register(registry)
                .increment();
    }

    @Override
    public void outboundCompleted(
            ObservationDefinition definition,
            Outcome outcome,
            StatusClass status,
            long durationMs,
            AcknowledgementOutcome acknowledgement) {
        Counter.builder("partner_observability_http_interactions_total")
                .tags(common(definition, outcome, status, "outbound"))
                .register(registry).increment();
        Timer.builder("partner_observability_http_duration_seconds")
                .tags("service", service, "partner_slot", definition.partnerContext().partnerSlot(),
                        "api", definition.name(), "outcome", value(outcome))
                .register(registry).record(Duration.ofMillis(durationMs));
        if (acknowledgement != null) {
            Counter.builder("partner_observability_async_acknowledgements_total")
                    .tags("service", service, "partner_slot", definition.partnerContext().partnerSlot(),
                            "api", definition.name(), "ack_outcome", value(acknowledgement),
                            "status_class", value(status))
                    .register(registry).increment();
        }
    }

    @Override
    public void callbackReceived(ObservationDefinition definition, DeliveryClassification classification) {
        Counter.builder("partner_observability_callback_deliveries_total")
                .tags("service", service, "partner_slot", definition.partnerContext().partnerSlot(),
                        "api", definition.name(), "delivery_class", value(classification))
                .register(registry).increment();
    }

    @Override
    public void callbackProcessed(
            ObservationDefinition definition,
            Outcome outcome,
            long durationMs,
            ProcessingMode mode,
            ProcessingPhase phase) {
        Counter.builder("partner_observability_callback_processing_total")
                .tags("service", service, "partner_slot", definition.partnerContext().partnerSlot(),
                        "api", definition.name(), "processing_mode", value(mode),
                        "processing_phase", value(phase), "outcome", value(outcome))
                .register(registry).increment();
        Timer.builder("partner_observability_callback_processing_duration_seconds")
                .tags("service", service, "partner_slot", definition.partnerContext().partnerSlot(),
                        "api", definition.name(), "processing_mode", value(mode), "outcome", value(outcome))
                .register(registry).record(Duration.ofMillis(durationMs));
    }

    @Override
    public void callbackResponded(
            ObservationDefinition definition, Outcome outcome, StatusClass status, TransportOutcome transport) {
        Counter.builder("partner_observability_callback_response_total")
                .tags("service", service, "partner_slot", definition.partnerContext().partnerSlot(),
                        "api", definition.name(), "outcome", value(outcome),
                        "status_class", value(status), "result", value(transport))
                .register(registry).increment();
    }

    @Override
    public void callbackDenied(String reason) {
        Counter.builder("partner_observability_callback_ingress_denied_total")
                .tags("service", service, "reason", reason)
                .register(registry).increment();
    }

    @Override
    public void transportSecurityFailure(
            ObservationDefinition definition, TransportFailureClass failureClass) {
        Counter.builder("partner_observability_transport_security_failures_total")
                .tags("service", service, "api", definition.name(), "direction", "outbound",
                        "interaction_kind", definition.exchangeMode()
                                == com.partner.observability.core.model.ExchangeMode.SYNC
                                ? "sync_outbound" : "async_initiation",
                        "transport_failure_class", value(failureClass))
                .register(registry).increment();
    }

    private String[] common(
            ObservationDefinition definition, Outcome outcome, StatusClass status, String direction) {
        return new String[] {
            "service", service,
            "partner_slot", definition.partnerContext().partnerSlot(),
            "api", definition.name(),
            "interaction_kind", definition.exchangeMode() == com.partner.observability.core.model.ExchangeMode.SYNC
                    ? "sync_outbound" : "async_initiation",
            "direction", direction,
            "outcome", value(outcome),
            "status_class", value(status)
        };
    }

    private String value(Enum<?> value) {
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
}
