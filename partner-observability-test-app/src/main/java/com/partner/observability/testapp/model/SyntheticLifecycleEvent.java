package com.partner.observability.testapp.model;

import java.time.Instant;

/** One bounded, payload-free event in a synthetic async fixture journey. */
public record SyntheticLifecycleEvent(
        int sequence,
        Instant occurredAt,
        SyntheticLifecycleStage stage,
        String callbackAttemptId,
        String outcome,
        Integer httpStatus) {}
