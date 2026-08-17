package com.partner.observability.testapp.telemetry;

import com.partner.observability.core.model.TelemetryEnvelope;
import com.partner.observability.core.publish.PublishBatch;
import com.partner.observability.core.publish.TelemetryPublisher;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.springframework.stereotype.Component;

/** Bounded, process-local collector of already-safe records for deterministic fixture assertions. */
@Component
public final class SyntheticTelemetryCollector implements TelemetryPublisher {
    private static final int MAX_RECORDS = 4096;
    private final ArrayDeque<TelemetryEnvelope<?>> records = new ArrayDeque<>(MAX_RECORDS);
    private volatile boolean failPublishing;
    private volatile CountDownLatch publishGate;

    @Override
    public synchronized void publish(PublishBatch batch) {
        CountDownLatch gate = publishGate;
        if (gate != null) {
            try {
                gate.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("SYNTHETIC_TELEMETRY_PUBLISH_INTERRUPTED");
            }
        }
        if (failPublishing) {
            throw new IllegalStateException("SYNTHETIC_TELEMETRY_BACKEND_FAILURE");
        }
        batch.submissions().forEach(submission -> {
            if (records.size() == MAX_RECORDS) records.removeFirst();
            records.addLast(submission.envelope());
        });
    }

    public synchronized List<TelemetryEnvelope<?>> snapshot() {
        return List.copyOf(new ArrayList<>(records));
    }

    public synchronized void clear() {
        records.clear();
        failPublishing = false;
        releasePublishing();
    }

    public void failPublishing(boolean value) {
        failPublishing = value;
    }

    public void pausePublishing() {
        publishGate = new CountDownLatch(1);
    }

    public void releasePublishing() {
        CountDownLatch gate = publishGate;
        publishGate = null;
        if (gate != null) gate.countDown();
    }
}
