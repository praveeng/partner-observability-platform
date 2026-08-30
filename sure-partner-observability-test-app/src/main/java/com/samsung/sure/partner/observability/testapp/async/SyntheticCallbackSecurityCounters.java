package com.samsung.sure.partner.observability.testapp.async;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** Fixed-dimension internal-only counters for rejected synthetic callback ingress. */
@Component
public final class SyntheticCallbackSecurityCounters {

    private final Map<DenialReason, AtomicLong> counters = new EnumMap<>(DenialReason.class);

    public SyntheticCallbackSecurityCounters() {
        for (DenialReason reason : DenialReason.values()) {
            counters.put(reason, new AtomicLong());
        }
    }

    public void increment(DenialReason reason) {
        counters.get(reason).incrementAndGet();
    }

    public Map<DenialReason, Long> snapshot() {
        Map<DenialReason, Long> result = new EnumMap<>(DenialReason.class);
        counters.forEach((reason, count) -> result.put(reason, count.get()));
        return Map.copyOf(result);
    }

    public enum DenialReason {
        AUTHENTICATION_FAILED,
        WRONG_PARTNER,
        UNKNOWN_RUN,
        OVERSIZED_BODY
    }
}
