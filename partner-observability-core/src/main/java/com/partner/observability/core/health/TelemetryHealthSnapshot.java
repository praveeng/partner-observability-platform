package com.partner.observability.core.health;

import com.partner.observability.core.dispatch.DropReason;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

public record TelemetryHealthSnapshot(
        HealthState state,
        boolean dispatcherAlive,
        long captureAttempts,
        long enqueued,
        long publishedEvents,
        long publishedBatches,
        long publisherFailures,
        Map<DropReason, Long> drops,
        int highQueueEvents,
        long highQueueBytes,
        int normalQueueEvents,
        long normalQueueBytes,
        Optional<Instant> lastSuccessfulPublishAt) {

    public TelemetryHealthSnapshot {
        drops = Map.copyOf(drops);
        lastSuccessfulPublishAt = lastSuccessfulPublishAt == null ? Optional.empty() : lastSuccessfulPublishAt;
    }

    public long totalDrops() {
        return drops.values().stream().mapToLong(Long::longValue).sum();
    }

    public long drops(DropReason reason) {
        return drops.getOrDefault(reason, 0L);
    }
}
