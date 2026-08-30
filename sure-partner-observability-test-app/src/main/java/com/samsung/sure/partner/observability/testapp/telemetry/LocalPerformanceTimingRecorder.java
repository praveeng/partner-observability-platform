package com.samsung.sure.partner.observability.testapp.telemetry;

import com.samsung.sure.partner.observability.autoconfigure.ObservationPerformanceRecorder;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Local-only fixed-memory microsecond histogram. It never stores request or payload data. */
@Component
@ConditionalOnProperty(name = "local-synthetic.performance-controls-enabled", havingValue = "true")
public final class LocalPerformanceTimingRecorder implements ObservationPerformanceRecorder {
    private static final int MAX_MICROSECOND_BUCKET = 100_000;
    private final ConcurrentHashMap<String, Histogram> values = new ConcurrentHashMap<>();

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void recordNanos(String operation, long elapsedNanos) {
        values.computeIfAbsent(operation, ignored -> new Histogram()).record(elapsedNanos);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.put(entry.getKey(), entry.getValue().snapshot()));
        return Map.copyOf(result);
    }

    public void reset() {
        values.clear();
    }

    private static final class Histogram {
        private final LongAdder[] buckets = new LongAdder[MAX_MICROSECOND_BUCKET + 2];
        private final LongAdder count = new LongAdder();
        private final LongAdder nanos = new LongAdder();
        private final LongAdder overflow = new LongAdder();

        private Histogram() {
            Arrays.setAll(buckets, ignored -> new LongAdder());
        }

        private void record(long elapsedNanos) {
            long micros = elapsedNanos / 1_000L;
            int bucket = (int) Math.min(MAX_MICROSECOND_BUCKET + 1L, micros);
            buckets[bucket].increment();
            if (bucket > MAX_MICROSECOND_BUCKET) overflow.increment();
            count.increment();
            nanos.add(elapsedNanos);
        }

        private Map<String, Object> snapshot() {
            long total = count.sum();
            return Map.of(
                    "count", total,
                    "meanMicroseconds", total == 0 ? 0.0 : nanos.sum() / 1_000.0 / total,
                    "p50Microseconds", percentile(total, 0.50),
                    "p95Microseconds", percentile(total, 0.95),
                    "p99Microseconds", percentile(total, 0.99),
                    "overflowCount", overflow.sum());
        }

        private long percentile(long total, double percentile) {
            if (total == 0) return 0;
            long target = (long) Math.ceil(total * percentile);
            long seen = 0;
            for (int index = 0; index < buckets.length; index++) {
                seen += buckets[index].sum();
                if (seen >= target) return index;
            }
            return MAX_MICROSECOND_BUCKET + 1L;
        }
    }
}
