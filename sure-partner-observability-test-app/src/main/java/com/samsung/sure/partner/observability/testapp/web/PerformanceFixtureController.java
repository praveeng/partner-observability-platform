package com.samsung.sure.partner.observability.testapp.web;

import com.samsung.sure.partner.observability.core.health.TelemetryHealth;
import com.samsung.sure.partner.observability.core.health.TelemetryHealthSnapshot;
import com.samsung.sure.partner.observability.core.model.AsyncAcknowledgementRecord;
import com.samsung.sure.partner.observability.core.model.CallbackRequestRecord;
import com.samsung.sure.partner.observability.core.model.OutboundApiRequestRecord;
import com.samsung.sure.partner.observability.core.model.OutboundApiResponseRecord;
import com.samsung.sure.partner.observability.core.model.TelemetryEnvelope;
import com.samsung.sure.partner.observability.core.payload.PayloadStatus;
import com.samsung.sure.partner.observability.core.payload.SanitizationResult;
import com.samsung.sure.partner.observability.core.payload.SanitizedValue;
import com.samsung.sure.partner.observability.testapp.telemetry.SyntheticTelemetryCollector;
import com.samsung.sure.partner.observability.testapp.telemetry.LocalPerformanceTimingRecorder;
import com.samsung.sure.partner.observability.testapp.async.SyntheticAsyncLifecycleStore;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Local-only bounded controls and payload-free counters used by the B003 harness. */
@RestController
@RequestMapping("/fixture/performance")
@ConditionalOnProperty(name = "local-synthetic.performance-controls-enabled", havingValue = "true")
public final class PerformanceFixtureController {
    private static final Pattern LARGE_BASE64 = Pattern.compile("[A-Za-z0-9+/]{1024,}={0,2}");
    private static final int HIGH_EVENT_CAP = 256;
    private static final long HIGH_BYTE_CAP = 4L * 1024 * 1024;
    private static final int NORMAL_EVENT_CAP = 1024;
    private static final long NORMAL_BYTE_CAP = 16L * 1024 * 1024;

    private final Optional<TelemetryHealth> health;
    private final SyntheticTelemetryCollector collector;
    private final LocalPerformanceTimingRecorder timings;
    private final SyntheticAsyncLifecycleStore lifecycleStore;

    public PerformanceFixtureController(
            Optional<TelemetryHealth> health,
            SyntheticTelemetryCollector collector,
            LocalPerformanceTimingRecorder timings,
            SyntheticAsyncLifecycleStore lifecycleStore) {
        this.health = health;
        this.collector = collector;
        this.timings = timings;
        this.lifecycleStore = lifecycleStore;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        if (health.isEmpty()) {
            Map<String, Object> disabled = new LinkedHashMap<>();
            disabled.put("state", "DISABLED");
            disabled.put("dispatcherAlive", false);
            disabled.put("captureAttempts", 0);
            disabled.put("enqueued", 0);
            disabled.put("publishedEvents", 0);
            disabled.put("publishedBatches", 0);
            disabled.put("publisherFailures", 0);
            disabled.put("drops", Map.of());
            disabled.put("totalDrops", 0);
            disabled.put("highQueueEvents", 0);
            disabled.put("highQueueBytes", 0);
            disabled.put("normalQueueEvents", 0);
            disabled.put("normalQueueBytes", 0);
            disabled.put("highEventCap", HIGH_EVENT_CAP);
            disabled.put("highByteCap", HIGH_BYTE_CAP);
            disabled.put("normalEventCap", NORMAL_EVENT_CAP);
            disabled.put("normalByteCap", NORMAL_BYTE_CAP);
            return Map.copyOf(disabled);
        }
        TelemetryHealthSnapshot value = health.get().snapshot();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", value.state().name());
        result.put("dispatcherAlive", value.dispatcherAlive());
        result.put("captureAttempts", value.captureAttempts());
        result.put("enqueued", value.enqueued());
        result.put("publishedEvents", value.publishedEvents());
        result.put("publishedBatches", value.publishedBatches());
        result.put("publisherFailures", value.publisherFailures());
        result.put("drops", value.drops());
        result.put("totalDrops", value.totalDrops());
        result.put("highQueueEvents", value.highQueueEvents());
        result.put("highQueueBytes", value.highQueueBytes());
        result.put("normalQueueEvents", value.normalQueueEvents());
        result.put("normalQueueBytes", value.normalQueueBytes());
        result.put("highEventCap", HIGH_EVENT_CAP);
        result.put("highByteCap", HIGH_BYTE_CAP);
        result.put("normalEventCap", NORMAL_EVENT_CAP);
        result.put("normalByteCap", NORMAL_BYTE_CAP);
        return Map.copyOf(result);
    }

    @PostMapping("/publisher/{state}")
    public Map<String, String> publisher(@PathVariable String state) {
        switch (state) {
            case "pause" -> collector.pausePublishing();
            case "release" -> collector.releasePublishing();
            case "fail" -> collector.failPublishing(true);
            case "healthy" -> {
                collector.failPublishing(false);
                collector.releasePublishing();
            }
            default -> throw new IllegalArgumentException("SYNTHETIC_PERFORMANCE_STATE_UNKNOWN");
        }
        return Map.of("fixtureClassification", "SYNTHETIC_ONLY", "publisherState", state);
    }

    @PostMapping("/reset")
    public Map<String, String> reset() {
        collector.clear();
        timings.reset();
        lifecycleStore.resetPerformanceState();
        return Map.of("fixtureClassification", "SYNTHETIC_ONLY", "result", "RESET");
    }

    @GetMapping("/callbacks")
    public Map<String, Long> callbacks() {
        return lifecycleStore.performanceSnapshot();
    }

    @GetMapping("/timings")
    public Map<String, Object> timings() {
        return Map.of("fixtureClassification", "SYNTHETIC_ONLY", "operations", timings.snapshot());
    }

    @GetMapping("/jvm")
    public Map<String, Object> jvm() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long[] deadlocked = threads.findDeadlockedThreads();
        long collections = 0;
        long collectionMillis = 0;
        for (GarbageCollectorMXBean collectorBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            collections += Math.max(0, collectorBean.getCollectionCount());
            collectionMillis += Math.max(0, collectorBean.getCollectionTime());
        }
        return Map.of(
                "fixtureClassification", "SYNTHETIC_ONLY",
                "heapUsedBytes", memory.getHeapMemoryUsage().getUsed(),
                "heapCommittedBytes", memory.getHeapMemoryUsage().getCommitted(),
                "heapMaxBytes", memory.getHeapMemoryUsage().getMax(),
                "threadCount", threads.getThreadCount(),
                "deadlockDetected", deadlocked != null && deadlocked.length > 0,
                "gcCollectionCount", collections,
                "gcCollectionTimeMilliseconds", collectionMillis);
    }

    @GetMapping("/records")
    public Map<String, Object> records() {
        List<TelemetryEnvelope<?>> values = collector.snapshot();
        EnumMap<PayloadStatus, Long> statuses = new EnumMap<>(PayloadStatus.class);
        long binaryOmissions = 0;
        long binaryOrOversizeOmissions = 0;
        int maximumSanitizedPayloadBytes = 0;
        boolean prohibitedPayloadExposed = false;
        for (TelemetryEnvelope<?> envelope : values) {
            statuses.merge(envelope.payloadStatus(), 1L, Long::sum);
            SanitizationResult result = payload(envelope);
            if (result != null) {
                if (result.omittedBinary().isPresent()) binaryOmissions++;
                if (result.status() == PayloadStatus.BINARY
                        || result.status() == PayloadStatus.BASE64
                        || result.status() == PayloadStatus.OVERSIZE) {
                    binaryOrOversizeOmissions++;
                }
                if (result.payload().isPresent()) {
                    maximumSanitizedPayloadBytes = Math.max(
                            maximumSanitizedPayloadBytes, result.payload().get().jsonUtf8Bytes());
                    prohibitedPayloadExposed |= containsProhibitedPayload(result.payload().get().value());
                }
            }
        }
        return Map.of(
                "fixtureClassification", "SYNTHETIC_ONLY",
                "retainedRecordCount", values.size(),
                "payloadStatuses", statuses,
                "binaryOmissionRecordCount", binaryOmissions,
                "binaryOrOversizeOmissionRecordCount", binaryOrOversizeOmissions,
                "maximumSanitizedPayloadBytes", maximumSanitizedPayloadBytes,
                "rawPayloadsExposed", prohibitedPayloadExposed);
    }

    private SanitizationResult payload(TelemetryEnvelope<?> envelope) {
        if (envelope.body() instanceof OutboundApiRequestRecord value) return value.payload();
        if (envelope.body() instanceof OutboundApiResponseRecord value) return value.payload();
        if (envelope.body() instanceof AsyncAcknowledgementRecord value) return value.payload();
        if (envelope.body() instanceof CallbackRequestRecord value) return value.payload();
        return null;
    }

    private boolean containsProhibitedPayload(SanitizedValue value) {
        return value.accept(new SanitizedValue.Visitor<>() {
            @Override
            public Boolean string(String candidate) {
                return LARGE_BASE64.matcher(candidate).matches()
                        || candidate.contains("SYNTHETIC_FORBIDDEN_BASE64_BODY");
            }

            @Override
            public Boolean number(java.math.BigDecimal ignored) {
                return false;
            }

            @Override
            public Boolean bool(boolean ignored) {
                return false;
            }

            @Override
            public Boolean object(Map<String, SanitizedValue> fields) {
                return fields.values().stream().anyMatch(PerformanceFixtureController.this::containsProhibitedPayload);
            }

            @Override
            public Boolean array(List<SanitizedValue> elements) {
                return elements.stream().anyMatch(PerformanceFixtureController.this::containsProhibitedPayload);
            }

            @Override
            public Boolean nil() {
                return false;
            }
        });
    }
}
