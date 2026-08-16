package com.partner.observability.core.dispatch;

import com.partner.observability.core.context.PartnerContext;
import com.partner.observability.core.health.HealthState;
import com.partner.observability.core.health.TelemetryHealth;
import com.partner.observability.core.health.TelemetryHealth.TelemetryPriorityView;
import com.partner.observability.core.policy.KillSwitchState;
import com.partner.observability.core.policy.ObservabilityKillSwitches;
import com.partner.observability.core.publish.PublishBatch;
import com.partner.observability.core.publish.TelemetryPublisher;
import com.partner.observability.core.time.SystemTimeSource;
import com.partner.observability.core.time.TimeSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * Two fixed-capacity MPSC queues and one daemon publisher thread. Producer methods perform no
 * backend I/O, sleep, retry, eviction, or blocking queue operation.
 */
public final class BoundedAsyncDispatcher implements AutoCloseable {

    private final TelemetryPublisher publisher;
    private final DispatcherConfig config;
    private final ObservabilityKillSwitches killSwitches;
    private final TelemetryHealth health;
    private final TimeSource timeSource;
    private final BoundedTelemetryQueue highQueue;
    private final BoundedTelemetryQueue normalQueue;
    private final AtomicBoolean accepting = new AtomicBoolean();
    private final AtomicBoolean runRequested = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean forcedShutdown = new AtomicBoolean();
    private final AtomicReference<List<TelemetrySubmission>> inFlight = new AtomicReference<>(List.of());
    private final AtomicReference<RetryBatch> retrySlot = new AtomicReference<>();
    private final Thread dispatcherThread;

    public BoundedAsyncDispatcher(
            TelemetryPublisher publisher,
            DispatcherConfig config,
            ObservabilityKillSwitches killSwitches,
            TelemetryHealth health) {
        this(publisher, config, killSwitches, health, SystemTimeSource.INSTANCE);
    }

    public BoundedAsyncDispatcher(
            TelemetryPublisher publisher,
            DispatcherConfig config,
            ObservabilityKillSwitches killSwitches,
            TelemetryHealth health,
            TimeSource timeSource) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.config = Objects.requireNonNull(config, "config");
        this.killSwitches = Objects.requireNonNull(killSwitches, "killSwitches");
        this.health = Objects.requireNonNull(health, "health");
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
        highQueue = new BoundedTelemetryQueue(config.highEventCapacity(), config.highByteCapacity());
        normalQueue = new BoundedTelemetryQueue(config.normalEventCapacity(), config.normalByteCapacity());
        dispatcherThread = new Thread(this::dispatchLoop, "partner-observability-dispatcher");
        dispatcherThread.setDaemon(true);
    }

    public synchronized void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        health.state(HealthState.STARTING);
        accepting.set(true);
        runRequested.set(true);
        dispatcherThread.start();
    }

    public boolean submit(TelemetrySubmission submission) {
        health.captureAttempted();
        try {
            Objects.requireNonNull(submission, "submission");
            if (!accepting.get()) {
                return drop(DropReason.SHUTDOWN_TIMEOUT);
            }
            KillSwitchState switches = killSwitches.snapshot();
            if (!switches.observabilityEnabled() || !switches.exportEnabled()) {
                return drop(DropReason.DISABLED);
            }
            if (submission.channel() == TelemetryChannel.LOG && !switches.logsEnabled()) {
                return drop(DropReason.DISABLED);
            }
            if (submission.channel() == TelemetryChannel.EVENT && !switches.eventsEnabled()) {
                return drop(DropReason.DISABLED);
            }
            if (submission.serializedSizeBytes() > config.maxEventBytes()) {
                return drop(DropReason.OVERSIZE);
            }

            BoundedTelemetryQueue target = submission.priority() == TelemetryPriority.HIGH ? highQueue : normalQueue;
            BoundedTelemetryQueue.OfferResult result = target.offer(submission);
            updateGauges();
            if (result == BoundedTelemetryQueue.OfferResult.EVENT_CAPACITY) {
                return drop(DropReason.QUEUE_EVENT_CAPACITY);
            }
            if (result == BoundedTelemetryQueue.OfferResult.BYTE_CAPACITY) {
                return drop(DropReason.QUEUE_BYTE_CAPACITY);
            }
            health.enqueued();
            LockSupport.unpark(dispatcherThread);
            return true;
        } catch (RuntimeException exception) {
            return drop(DropReason.SERIALIZATION);
        }
    }

    /** Contains sanitizer/record-construction exceptions before they can reach business code. */
    public boolean submitSafely(Supplier<TelemetrySubmission> safeSubmissionFactory) {
        try {
            return submit(safeSubmissionFactory.get());
        } catch (RuntimeException exception) {
            health.captureAttempted();
            return drop(DropReason.MALFORMED);
        }
    }

    public TelemetryHealth health() {
        return health;
    }

    @Override
    public synchronized void close() {
        if (!accepting.getAndSet(false) && !dispatcherThread.isAlive()) {
            return;
        }
        health.state(HealthState.STOPPING);
        runRequested.set(false);
        LockSupport.unpark(dispatcherThread);
        long deadline = System.nanoTime() + config.shutdownTimeout().toNanos();
        joinUntil(deadline);
        if (dispatcherThread.isAlive()) {
            forcedShutdown.set(true);
            drainRemaining(DropReason.SHUTDOWN_TIMEOUT);
            RetryBatch retry = retrySlot.getAndSet(null);
            if (retry != null) {
                health.dropped(DropReason.SHUTDOWN_TIMEOUT, retry.submissions().size());
            }
            List<TelemetrySubmission> active = inFlight.getAndSet(List.of());
            health.dropped(DropReason.SHUTDOWN_TIMEOUT, active.size());
            dispatcherThread.interrupt();
            health.state(HealthState.DEGRADED);
        } else {
            health.state(HealthState.STOPPED);
        }
        updateGauges();
    }

    private void dispatchLoop() {
        health.dispatcherAlive(true);
        health.state(HealthState.RUNNING);
        try {
            while (runRequested.get() || hasPendingWork()) {
                KillSwitchState switches = killSwitches.snapshot();
                if (!switches.observabilityEnabled() || !switches.exportEnabled()) {
                    drainRemaining(DropReason.DISABLED);
                    RetryBatch retry = retrySlot.getAndSet(null);
                    if (retry != null) {
                        health.dropped(DropReason.DISABLED, retry.submissions().size());
                    }
                    park();
                    continue;
                }
                boolean work = publishRetryIfReady();
                work |= drainAndPublish(highQueue);
                for (int normalBatches = 0; normalBatches < 3; normalBatches++) {
                    work |= drainAndPublish(normalQueue);
                    if (normalQueue.isEmpty()) {
                        break;
                    }
                }
                updateGauges();
                if (!work) {
                    park();
                }
            }
        } finally {
            health.dispatcherAlive(false);
        }
    }

    private boolean drainAndPublish(BoundedTelemetryQueue queue) {
        List<TelemetrySubmission> drained = new ArrayList<>(config.maxBatchEvents());
        int bytes = 0;
        while (drained.size() < config.maxBatchEvents()) {
            TelemetrySubmission next = queue.peek();
            if (next == null || bytes + next.serializedSizeBytes() > config.maxBatchBytes()) {
                break;
            }
            TelemetrySubmission polled = queue.poll();
            if (polled != null) {
                drained.add(polled);
                bytes += polled.serializedSizeBytes();
            }
        }
        if (drained.isEmpty()) {
            return false;
        }
        Map<PartnerContext.RoutingKey, List<TelemetrySubmission>> partitions = new LinkedHashMap<>();
        for (TelemetrySubmission submission : drained) {
            partitions.computeIfAbsent(
                            submission.envelope().partnerContext().routingKey(), ignored -> new ArrayList<>())
                    .add(submission);
        }
        for (List<TelemetrySubmission> partition : partitions.values()) {
            publishPartition(partition, false);
        }
        return true;
    }

    private void publishPartition(List<TelemetrySubmission> submissions, boolean retryAttempt) {
        PartnerContext context = submissions.get(0).envelope().partnerContext();
        int bytes = submissions.stream().mapToInt(TelemetrySubmission::serializedSizeBytes).sum();
        List<TelemetrySubmission> active = List.copyOf(submissions);
        inFlight.set(active);
        try {
            publisher.publish(new PublishBatch(context, submissions, bytes));
            health.published(submissions.size());
            health.state(HealthState.RUNNING);
        } catch (Exception exception) {
            if (!forcedShutdown.get()) {
                health.publisherFailed();
                if (retryAttempt || !scheduleRetry(submissions)) {
                    health.dropped(DropReason.EXPORT_FAILURE, submissions.size());
                }
            }
        } finally {
            inFlight.compareAndSet(active, List.of());
        }
    }

    private boolean scheduleRetry(List<TelemetrySubmission> submissions) {
        long baseDelay = config.retryDelay().toNanos();
        long jitterRange = Math.min(Duration.ofMillis(50).toNanos(), baseDelay / 4);
        long hash = submissions.get(0).envelope().eventId().getLeastSignificantBits();
        long jitter = jitterRange == 0 ? 0 : Math.floorMod(hash, (jitterRange * 2) + 1) - jitterRange;
        return retrySlot.compareAndSet(
                null, new RetryBatch(List.copyOf(submissions), timeSource.monotonicNanos() + baseDelay + jitter));
    }

    private boolean publishRetryIfReady() {
        RetryBatch retry = retrySlot.get();
        if (retry == null || timeSource.monotonicNanos() < retry.availableAtNanos()) {
            return false;
        }
        if (!retrySlot.compareAndSet(retry, null)) {
            return false;
        }
        publishPartition(retry.submissions(), true);
        return true;
    }

    private boolean drop(DropReason reason) {
        health.dropped(reason);
        return false;
    }

    private long drainRemaining(DropReason reason) {
        long count = drainQueue(highQueue) + drainQueue(normalQueue);
        health.dropped(reason, count);
        updateGauges();
        return count;
    }

    private long drainQueue(BoundedTelemetryQueue queue) {
        long count = 0;
        while (queue.poll() != null) {
            count++;
        }
        return count;
    }

    private boolean hasPendingWork() {
        return !highQueue.isEmpty() || !normalQueue.isEmpty() || retrySlot.get() != null;
    }

    private void updateGauges() {
        health.queueGauge(TelemetryPriorityView.HIGH, highQueue.size(), highQueue.bytes());
        health.queueGauge(TelemetryPriorityView.NORMAL, normalQueue.size(), normalQueue.bytes());
    }

    private void park() {
        LockSupport.parkNanos(this, config.flushInterval().toNanos());
    }

    private void joinUntil(long deadlineNanos) {
        boolean interrupted = false;
        while (dispatcherThread.isAlive()) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                long millis = Math.max(1, Duration.ofNanos(remaining).toMillis());
                dispatcherThread.join(millis);
            } catch (InterruptedException exception) {
                interrupted = true;
                break;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private record RetryBatch(List<TelemetrySubmission> submissions, long availableAtNanos) {}
}
