package com.partner.observability.core.dispatch;

import java.time.Duration;
import java.util.Objects;

public record DispatcherConfig(
        int highEventCapacity,
        long highByteCapacity,
        int normalEventCapacity,
        long normalByteCapacity,
        int maxEventBytes,
        int maxBatchEvents,
        int maxBatchBytes,
        Duration flushInterval,
        Duration retryDelay,
        Duration shutdownTimeout,
        DropPolicy dropPolicy) {

    public static final int HARD_MAX_EVENT_BYTES = 64 * 1024;
    public static final int HARD_MAX_BATCH_EVENTS = 128;
    public static final int HARD_MAX_BATCH_BYTES = 256 * 1024;

    public DispatcherConfig {
        powerOfTwoRange(highEventCapacity, 64, 1024, "highEventCapacity");
        powerOfTwoRange(normalEventCapacity, 128, 8192, "normalEventCapacity");
        range(highByteCapacity, maxEventBytes, 16L * 1024 * 1024, "highByteCapacity");
        range(normalByteCapacity, maxEventBytes, 64L * 1024 * 1024, "normalByteCapacity");
        range(maxEventBytes, 1, HARD_MAX_EVENT_BYTES, "maxEventBytes");
        range(maxBatchEvents, 1, HARD_MAX_BATCH_EVENTS, "maxBatchEvents");
        range(maxBatchBytes, maxEventBytes, HARD_MAX_BATCH_BYTES, "maxBatchBytes");
        durationRange(flushInterval, Duration.ofMillis(50), Duration.ofSeconds(1), "flushInterval");
        durationRange(retryDelay, Duration.ofMillis(1), Duration.ofSeconds(1), "retryDelay");
        durationRange(shutdownTimeout, Duration.ofMillis(1), Duration.ofSeconds(2), "shutdownTimeout");
        if (dropPolicy != DropPolicy.DROP_NEWEST) {
            throw new IllegalArgumentException("only DROP_NEWEST is permitted");
        }
    }

    public static DispatcherConfig defaults() {
        return new DispatcherConfig(
                256,
                4L * 1024 * 1024,
                1024,
                16L * 1024 * 1024,
                HARD_MAX_EVENT_BYTES,
                HARD_MAX_BATCH_EVENTS,
                HARD_MAX_BATCH_BYTES,
                Duration.ofMillis(200),
                Duration.ofMillis(200),
                Duration.ofSeconds(2),
                DropPolicy.DROP_NEWEST);
    }

    private static void powerOfTwoRange(int value, int minimum, int maximum, String name) {
        range(value, minimum, maximum, name);
        if ((value & (value - 1)) != 0) {
            throw new IllegalArgumentException(name + " must be a power of two");
        }
    }

    private static void range(long value, long minimum, long maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }

    private static void durationRange(Duration value, Duration minimum, Duration maximum, String name) {
        Objects.requireNonNull(value, name);
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(name + " is outside the allowed range");
        }
    }
}
