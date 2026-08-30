package com.samsung.sure.partner.observability.reactivetestapp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samsung.sure.partner.observability.autoconfigure.CallbackObservation;
import com.samsung.sure.partner.observability.autoconfigure.callback.ReactiveCallbackObservations;
import com.samsung.sure.partner.observability.core.dispatch.DispatcherConfig;
import com.samsung.sure.partner.observability.core.health.TelemetryHealth;
import com.samsung.sure.partner.observability.core.health.TelemetryHealthSnapshot;
import com.samsung.sure.partner.observability.core.model.CorrelationIdentifiers;
import com.samsung.sure.partner.observability.core.model.DeliveryClassification;
import com.samsung.sure.partner.observability.core.model.ParsingStatus;
import com.samsung.sure.partner.observability.core.model.ProcessingMode;
import com.samsung.sure.partner.observability.core.model.ProcessingPhase;
import com.samsung.sure.partner.observability.reactivetestapp.ReactiveFixtureSecurityConfiguration.ReactiveFixtureMetrics;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Test-only streaming callback surface; it never stores request bodies or business state. */
@RestController
@RequestMapping("/fixture/reactive")
public final class ReactiveFixtureController {
    private static final int STREAM_ELEMENT_BYTES = 2 * 1024;

    private final Optional<ReactiveCallbackObservations> observations;
    private final ReactiveFixtureMetrics metrics;
    private final Duration elementDelay;
    private final Optional<TelemetryHealth> telemetryHealth;
    private final ObjectMapper objectMapper;

    public ReactiveFixtureController(
            Optional<ReactiveCallbackObservations> observations,
            ReactiveFixtureMetrics metrics,
            Optional<TelemetryHealth> telemetryHealth,
            ObjectMapper objectMapper,
            @Value("${local-synthetic.reactive.element-delay:625ms}") Duration elementDelay) {
        this.observations = observations;
        this.metrics = metrics;
        this.telemetryHealth = telemetryHealth;
        this.objectMapper = objectMapper;
        this.elementDelay = elementDelay;
    }

    @PostMapping(value = "/stream/{partner}", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public Flux<Map<String, Object>> stream(
            @PathVariable String partner,
            @RequestBody Mono<Map<String, Object>> body,
            ServerWebExchange exchange) {
        return fixtureStream(partner, body, exchange, "stream");
    }

    @PostMapping(value = "/callback/{partner}", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public Flux<Map<String, Object>> callback(
            @PathVariable String partner,
            @RequestParam(defaultValue = "inline") String completion,
            @RequestBody Mono<Map<String, Object>> body,
            ServerWebExchange exchange) {
        return callbackFlow(partner, body, exchange, completion);
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> result = new LinkedHashMap<>(metrics.snapshot());
        DispatcherConfig limits = DispatcherConfig.defaults();
        telemetryHealth.ifPresent(value -> {
            TelemetryHealthSnapshot snapshot = value.snapshot();
            result.put("telemetryCaptureAttempts", snapshot.captureAttempts());
            result.put("telemetryEnqueued", snapshot.enqueued());
            result.put("telemetryDrops", snapshot.totalDrops());
            result.put("telemetryHighQueueEvents", snapshot.highQueueEvents());
            result.put("telemetryHighQueueBytes", snapshot.highQueueBytes());
            result.put("telemetryNormalQueueEvents", snapshot.normalQueueEvents());
            result.put("telemetryNormalQueueBytes", snapshot.normalQueueBytes());
        });
        result.putIfAbsent("telemetryCaptureAttempts", 0L);
        result.putIfAbsent("telemetryEnqueued", 0L);
        result.putIfAbsent("telemetryDrops", 0L);
        result.putIfAbsent("telemetryHighQueueEvents", 0);
        result.putIfAbsent("telemetryHighQueueBytes", 0L);
        result.putIfAbsent("telemetryNormalQueueEvents", 0);
        result.putIfAbsent("telemetryNormalQueueBytes", 0L);
        result.put("telemetryHighEventCap", limits.highEventCapacity());
        result.put("telemetryHighByteCap", limits.highByteCapacity());
        result.put("telemetryNormalEventCap", limits.normalEventCapacity());
        result.put("telemetryNormalByteCap", limits.normalByteCapacity());
        return Map.copyOf(result);
    }

    @GetMapping("/jvm")
    public Map<String, Object> jvm() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long[] deadlocked = threads.findDeadlockedThreads();
        long collections = 0;
        long collectionMillis = 0;
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            collections += Math.max(0, collector.getCollectionCount());
            collectionMillis += Math.max(0, collector.getCollectionTime());
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

    @PostMapping("/metrics/reset")
    public Map<String, String> resetMetrics() {
        metrics.reset();
        return Map.of("fixtureClassification", "SYNTHETIC_ONLY", "result", "RESET");
    }

    private Flux<Map<String, Object>> fixtureStream(
            String partner,
            Mono<Map<String, Object>> body,
            ServerWebExchange exchange,
            String completion) {
        String expected = partner.equals("alpha") ? "partner-alpha-fixture"
                : partner.equals("beta") ? "partner-beta-fixture" : "invalid";
        String trusted = exchange.getAttribute(ReactiveFixtureSecurityConfiguration.TRUSTED_PARTNER);
        Optional<CallbackObservation> observation = currentObservation(exchange);
        AtomicBoolean terminal = new AtomicBoolean();
        AtomicBoolean subscribed = new AtomicBoolean();
        Duration initialDelay = switch (completion) {
            case "short" -> Duration.ofMillis(500);
            case "long" -> Duration.ofSeconds(2);
            case "stream" -> Duration.ZERO;
            default -> Duration.ofMillis(50);
        };
        return body.flatMapMany(decoded -> {
                    if (!subscribed.compareAndSet(false, true)) {
                        metrics.doubleSubscription();
                        metrics.subscribed();
                        return Flux.error(new IllegalStateException("SYNTHETIC_DOUBLE_SUBSCRIPTION"));
                    }
                    metrics.subscribed();
                    if (!expected.equals(trusted)) {
                        metrics.contextConflict();
                        return Flux.error(new IllegalStateException("SYNTHETIC_CONTEXT_CONFLICT"));
                    }
                    observation.ifPresent(value -> {
                        value.received(
                                decoded,
                                identifiers(decoded),
                                DeliveryClassification.UNKNOWN,
                                ParsingStatus.PARSED);
                        value.authenticated();
                        value.validated();
                        value.processingStarted(ProcessingMode.INLINE);
                    });
                    return Flux.range(1, 32)
                            .delaySubscription(initialDelay)
                            .delayElements(elementDelay)
                            .map(sequence -> {
                                metrics.element();
                                return streamElement(partner, sequence);
                            });
                })
                .doOnComplete(() -> {
                    if (terminal.compareAndSet(false, true)) {
                        metrics.completed();
                        observation.ifPresent(value -> value.processingSucceeded(ProcessingMode.INLINE, false));
                    } else metrics.doubleTerminal();
                })
                .doOnCancel(() -> {
                    if (terminal.compareAndSet(false, true)) metrics.cancelled();
                    else metrics.doubleTerminal();
                })
                .doOnError(ignored -> {
                    if (terminal.compareAndSet(false, true)) metrics.errored();
                    else metrics.doubleTerminal();
                });
    }

    private Flux<Map<String, Object>> callbackFlow(
            String partner,
            Mono<Map<String, Object>> body,
            ServerWebExchange exchange,
            String completion) {
        String expected = partner.equals("alpha") ? "partner-alpha-fixture"
                : partner.equals("beta") ? "partner-beta-fixture" : "invalid";
        String trusted = exchange.getAttribute(ReactiveFixtureSecurityConfiguration.TRUSTED_PARTNER);
        Optional<CallbackObservation> observation = currentObservation(exchange);
        AtomicBoolean terminal = new AtomicBoolean();
        AtomicBoolean subscribed = new AtomicBoolean();
        boolean deferred = completion.equals("short") || completion.equals("long");
        Duration processingDelay = switch (completion) {
            case "short" -> Duration.ofMillis(500);
            case "long" -> Duration.ofSeconds(2);
            case "cancel" -> Duration.ofSeconds(20);
            default -> Duration.ofMillis(50);
        };
        return body.flatMapMany(decoded -> {
                    if (!subscribed.compareAndSet(false, true)) {
                        metrics.doubleSubscription();
                        metrics.subscribed();
                        return Flux.error(new IllegalStateException("SYNTHETIC_DOUBLE_SUBSCRIPTION"));
                    }
                    metrics.subscribed();
                    if (!expected.equals(trusted)) {
                        metrics.contextConflict();
                        return Flux.error(new IllegalStateException("SYNTHETIC_CONTEXT_CONFLICT"));
                    }
                    observation.ifPresent(value -> {
                        value.received(
                                decoded,
                                identifiers(decoded),
                                DeliveryClassification.UNKNOWN,
                                ParsingStatus.PARSED);
                        value.authenticated();
                        value.validated();
                        value.processingStarted(deferred ? ProcessingMode.BACKGROUND : ProcessingMode.INLINE);
                    });
                    if (deferred) {
                        if (!metrics.beginDeferred()) {
                            observation.ifPresent(value -> value.processingFailed(
                                    ProcessingMode.BACKGROUND,
                                    ProcessingPhase.BUSINESS_PROCESSING,
                                    "SYNTHETIC_DEFERRED_CAPACITY",
                                    true));
                            return Flux.error(new RejectedExecutionException(
                                    "SYNTHETIC_DEFERRED_CAPACITY"));
                        }
                        exchange.getResponse().setStatusCode(HttpStatus.ACCEPTED);
                        // Retain only the already-safe observation handle. The explicit fixture
                        // capacity and two-second maximum delay bound pending work; no request body
                        // or business DTO survives the acknowledgement.
                        Mono.delay(processingDelay)
                                .doOnSuccess(ignoredDelay -> {
                                    if (terminal.compareAndSet(false, true)) {
                                        metrics.completed();
                                        observation.ifPresent(value -> value.processingSucceeded(
                                                ProcessingMode.BACKGROUND, true));
                                    } else {
                                        metrics.doubleTerminal();
                                    }
                                })
                                .doOnError(ignoredError -> {
                                    if (terminal.compareAndSet(false, true)) {
                                        metrics.errored();
                                        observation.ifPresent(value -> value.processingFailed(
                                                ProcessingMode.BACKGROUND,
                                                ProcessingPhase.BUSINESS_PROCESSING,
                                                "SYNTHETIC_DEFERRED_FAILURE",
                                                true));
                                    } else {
                                        metrics.doubleTerminal();
                                    }
                                })
                                .doFinally(ignoredSignal -> metrics.endDeferred())
                                .subscribe();
                        return Flux.just(Map.<String, Object>of(
                                "fixtureClassification", "SYNTHETIC_ONLY",
                                "partner", partner,
                                "completion", completion,
                                "correlationId", stringValue(decoded, "correlationId").orElse("SYNTHETIC-NONE"),
                                "accepted", true));
                    }
                    return Mono.delay(processingDelay).map(sequence -> {
                        metrics.element();
                        return Map.<String, Object>of(
                                "fixtureClassification", "SYNTHETIC_ONLY",
                                "partner", partner,
                                "completion", completion,
                                "correlationId", stringValue(decoded, "correlationId").orElse("SYNTHETIC-NONE"));
                    }).flux();
                })
                .doOnComplete(() -> {
                    if (deferred) {
                        return;
                    }
                    if (terminal.compareAndSet(false, true)) {
                        metrics.completed();
                        observation.ifPresent(value -> value.processingSucceeded(ProcessingMode.INLINE, false));
                    } else metrics.doubleTerminal();
                })
                .doOnCancel(() -> {
                    if (deferred) {
                        return;
                    }
                    if (terminal.compareAndSet(false, true)) metrics.cancelled();
                    else metrics.doubleTerminal();
                })
                .doOnError(ignored -> {
                    if (terminal.compareAndSet(false, true)) metrics.errored();
                    else metrics.doubleTerminal();
                });
    }

    private CorrelationIdentifiers identifiers(Map<String, Object> decoded) {
        return new CorrelationIdentifiers(
                stringValue(decoded, "applicationId"),
                Optional.empty(),
                stringValue(decoded, "correlationId"),
                Optional.empty(),
                Optional.empty(),
                stringValue(decoded, "callbackReferenceId"),
                Optional.empty());
    }

    private Optional<String> stringValue(Map<String, Object> decoded, String key) {
        Object value = decoded.get(key);
        return value instanceof String candidate && !candidate.isBlank()
                ? Optional.of(candidate)
                : Optional.empty();
    }

    private Optional<CallbackObservation> currentObservation(ServerWebExchange exchange) {
        return observations.flatMap(value -> value.current(exchange));
    }

    private Map<String, Object> streamElement(String partner, int sequence) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fixtureClassification", "SYNTHETIC_ONLY");
        result.put("partner", partner);
        result.put("sequence", sequence);
        result.put("padding", "");
        try {
            int currentBytes = objectMapper.writeValueAsBytes(result).length;
            result.put("padding", "X".repeat(Math.max(0, STREAM_ELEMENT_BYTES - currentBytes)));
            return Map.copyOf(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("SYNTHETIC_STREAM_ELEMENT_ENCODING_FAILED", exception);
        }
    }
}
