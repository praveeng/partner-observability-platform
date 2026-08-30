package com.samsung.sure.partner.observability.core.time;

import java.time.Instant;

public interface TimeSource {
    Instant instant();

    long monotonicNanos();
}
