package com.partner.observability.core.dispatch;

import com.partner.observability.core.model.TelemetryEnvelope;
import java.util.Objects;

/** Already-safe bounded representation submitted by a producer. */
public record TelemetrySubmission(
        TelemetryEnvelope<?> envelope,
        int serializedSizeBytes,
        TelemetryPriority priority,
        TelemetryChannel channel) {

    public TelemetrySubmission {
        Objects.requireNonNull(envelope, "envelope");
        if (serializedSizeBytes < 1 || serializedSizeBytes > DispatcherConfig.HARD_MAX_EVENT_BYTES) {
            throw new IllegalArgumentException("serializedSizeBytes exceeds the event hard limit");
        }
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(channel, "channel");
    }
}
