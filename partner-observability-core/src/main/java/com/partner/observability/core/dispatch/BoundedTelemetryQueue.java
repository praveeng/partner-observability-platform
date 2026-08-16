package com.partner.observability.core.dispatch;

import java.util.concurrent.atomic.AtomicLong;
import org.jctools.queues.MpscArrayQueue;

final class BoundedTelemetryQueue {

    private final MpscArrayQueue<TelemetrySubmission> queue;
    private final long byteCapacity;
    private final AtomicLong bytes = new AtomicLong();

    BoundedTelemetryQueue(int eventCapacity, long byteCapacity) {
        queue = new MpscArrayQueue<>(eventCapacity);
        this.byteCapacity = byteCapacity;
    }

    OfferResult offer(TelemetrySubmission submission) {
        if (!reserveBytes(submission.serializedSizeBytes())) {
            return OfferResult.BYTE_CAPACITY;
        }
        if (!queue.offer(submission)) {
            bytes.addAndGet(-submission.serializedSizeBytes());
            return OfferResult.EVENT_CAPACITY;
        }
        return OfferResult.ACCEPTED;
    }

    TelemetrySubmission poll() {
        TelemetrySubmission submission = queue.poll();
        if (submission != null) {
            bytes.addAndGet(-submission.serializedSizeBytes());
        }
        return submission;
    }

    TelemetrySubmission peek() {
        return queue.peek();
    }

    int size() {
        return queue.size();
    }

    long bytes() {
        return bytes.get();
    }

    boolean isEmpty() {
        return queue.isEmpty();
    }

    private boolean reserveBytes(int requested) {
        long current = bytes.get();
        while (current <= byteCapacity - requested) {
            if (bytes.compareAndSet(current, current + requested)) {
                return true;
            }
            current = bytes.get();
        }
        return false;
    }

    enum OfferResult {
        ACCEPTED,
        EVENT_CAPACITY,
        BYTE_CAPACITY
    }
}
