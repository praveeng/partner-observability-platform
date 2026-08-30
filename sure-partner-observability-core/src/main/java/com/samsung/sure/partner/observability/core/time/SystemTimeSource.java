package com.samsung.sure.partner.observability.core.time;

import java.time.Instant;

public enum SystemTimeSource implements TimeSource {
    INSTANCE;

    @Override
    public Instant instant() {
        return Instant.now();
    }

    @Override
    public long monotonicNanos() {
        return System.nanoTime();
    }
}
