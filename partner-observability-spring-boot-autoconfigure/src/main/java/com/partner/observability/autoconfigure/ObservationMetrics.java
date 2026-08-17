package com.partner.observability.autoconfigure;

import com.partner.observability.core.model.AcknowledgementOutcome;
import com.partner.observability.core.model.DeliveryClassification;
import com.partner.observability.core.model.Outcome;
import com.partner.observability.core.model.ProcessingMode;
import com.partner.observability.core.model.ProcessingPhase;
import com.partner.observability.core.model.StatusClass;
import com.partner.observability.core.model.TelemetryRecordType;
import com.partner.observability.core.model.TransportOutcome;
import com.partner.observability.core.dispatch.TelemetryPriority;

public interface ObservationMetrics {
    void submitted(
            ObservationDefinition definition, TelemetryRecordType type, boolean accepted, TelemetryPriority priority);
    void outboundCompleted(
            ObservationDefinition definition, Outcome outcome, StatusClass status, long durationMs,
            AcknowledgementOutcome acknowledgement);
    void callbackReceived(ObservationDefinition definition, DeliveryClassification classification);
    void callbackProcessed(
            ObservationDefinition definition,
            Outcome outcome,
            long durationMs,
            ProcessingMode mode,
            ProcessingPhase phase);
    void callbackResponded(
            ObservationDefinition definition, Outcome outcome, StatusClass status, TransportOutcome transport);
    void callbackDenied(String reason);

    ObservationMetrics NONE = new ObservationMetrics() {
        public void submitted(ObservationDefinition d, TelemetryRecordType t, boolean a, TelemetryPriority p) {}
        public void outboundCompleted(ObservationDefinition d, Outcome o, StatusClass s, long ms, AcknowledgementOutcome a) {}
        public void callbackReceived(ObservationDefinition d, DeliveryClassification c) {}
        public void callbackProcessed(
                ObservationDefinition d, Outcome o, long ms, ProcessingMode m, ProcessingPhase p) {}
        public void callbackResponded(ObservationDefinition d, Outcome o, StatusClass s, TransportOutcome t) {}
        public void callbackDenied(String reason) {}
    };
}
