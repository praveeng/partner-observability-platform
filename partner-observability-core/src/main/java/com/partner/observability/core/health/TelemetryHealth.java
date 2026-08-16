package com.partner.observability.core.health;

import com.partner.observability.core.dispatch.DropReason;
import com.partner.observability.core.time.TimeSource;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/** Framework-neutral bounded-dimension health counters and gauges. */
public final class TelemetryHealth {

    private final TimeSource timeSource;
    private final AtomicReference<HealthState> state = new AtomicReference<>(HealthState.STOPPED);
    private final AtomicBoolean dispatcherAlive = new AtomicBoolean();
    private final LongAdder captureAttempts = new LongAdder();
    private final LongAdder enqueued = new LongAdder();
    private final LongAdder publishedEvents = new LongAdder();
    private final LongAdder publishedBatches = new LongAdder();
    private final LongAdder publisherFailures = new LongAdder();
    private final EnumMap<DropReason, LongAdder> drops = new EnumMap<>(DropReason.class);
    private final AtomicInteger highQueueEvents = new AtomicInteger();
    private final AtomicLong highQueueBytes = new AtomicLong();
    private final AtomicInteger normalQueueEvents = new AtomicInteger();
    private final AtomicLong normalQueueBytes = new AtomicLong();
    private final AtomicReference<Instant> lastSuccessfulPublishAt = new AtomicReference<>();

    public TelemetryHealth(TimeSource timeSource) {
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
        for (DropReason reason : DropReason.values()) {
            drops.put(reason, new LongAdder());
        }
    }

    public void captureAttempted() {
        captureAttempts.increment();
    }

    public void enqueued() {
        enqueued.increment();
    }

    public void dropped(DropReason reason) {
        drops.get(reason).increment();
    }

    public void dropped(DropReason reason, long count) {
        if (count > 0) {
            drops.get(reason).add(count);
        }
    }

    public void published(int count) {
        publishedBatches.increment();
        publishedEvents.add(count);
        lastSuccessfulPublishAt.set(timeSource.instant());
    }

    public void publisherFailed() {
        publisherFailures.increment();
        state.compareAndSet(HealthState.RUNNING, HealthState.DEGRADED);
    }

    public void state(HealthState next) {
        state.set(next);
    }

    public void dispatcherAlive(boolean alive) {
        dispatcherAlive.set(alive);
    }

    public void queueGauge(TelemetryPriorityView queue, int events, long bytes) {
        if (queue == TelemetryPriorityView.HIGH) {
            highQueueEvents.set(events);
            highQueueBytes.set(bytes);
        } else {
            normalQueueEvents.set(events);
            normalQueueBytes.set(bytes);
        }
    }

    public TelemetryHealthSnapshot snapshot() {
        EnumMap<DropReason, Long> dropSnapshot = new EnumMap<>(DropReason.class);
        drops.forEach((reason, counter) -> dropSnapshot.put(reason, counter.sum()));
        return new TelemetryHealthSnapshot(
                state.get(),
                dispatcherAlive.get(),
                captureAttempts.sum(),
                enqueued.sum(),
                publishedEvents.sum(),
                publishedBatches.sum(),
                publisherFailures.sum(),
                Map.copyOf(dropSnapshot),
                highQueueEvents.get(),
                highQueueBytes.get(),
                normalQueueEvents.get(),
                normalQueueBytes.get(),
                Optional.ofNullable(lastSuccessfulPublishAt.get()));
    }

    public enum TelemetryPriorityView {
        HIGH,
        NORMAL
    }
}
