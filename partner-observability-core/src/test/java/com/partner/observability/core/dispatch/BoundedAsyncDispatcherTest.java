package com.partner.observability.core.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.partner.observability.core.TestFixtures;
import com.partner.observability.core.context.PartnerContext;
import com.partner.observability.core.health.HealthState;
import com.partner.observability.core.health.TelemetryHealth;
import com.partner.observability.core.health.TelemetryHealthSnapshot;
import com.partner.observability.core.payload.BinaryKind;
import com.partner.observability.core.payload.FailClosedPayloadSanitizer;
import com.partner.observability.core.payload.PayloadInput;
import com.partner.observability.core.payload.PayloadSchema;
import com.partner.observability.core.payload.PayloadStatus;
import com.partner.observability.core.payload.SanitizationResult;
import com.partner.observability.core.policy.KillSwitchState;
import com.partner.observability.core.policy.ObservabilityKillSwitches;
import com.partner.observability.core.policy.PayloadCaptureMode;
import com.partner.observability.core.publish.PublishBatch;
import com.partner.observability.core.time.SystemTimeSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BoundedAsyncDispatcherTest {

    private final PartnerContext partnerA = TestFixtures.context("partner-a", "uk-dev-partner-a", "p001");

    @Test
    void fullEventQueueDropsNewestWithoutBlockingProducer() throws Exception {
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BoundedAsyncDispatcher dispatcher = dispatcher(batch -> {
            publishing.countDown();
            release.await();
        }, config(1024L * 1024, Duration.ofSeconds(1)));
        dispatcher.start();
        assertTrue(dispatcher.submit(TestFixtures.submission(partnerA, 512)));
        assertTrue(publishing.await(1, TimeUnit.SECONDS));

        long startedAt = System.nanoTime();
        for (int index = 0; index < 128; index++) {
            assertTrue(dispatcher.submit(TestFixtures.submission(partnerA, 512)));
        }
        assertFalse(dispatcher.submit(TestFixtures.submission(partnerA, 512)));
        long offerDuration = System.nanoTime() - startedAt;

        TelemetryHealthSnapshot saturated = dispatcher.health().snapshot();
        assertEquals(1, saturated.drops().get(DropReason.QUEUE_EVENT_CAPACITY));
        assertTrue(saturated.normalQueueEvents() <= 128);
        assertTrue(saturated.normalQueueBytes() <= 1024L * 1024);
        assertTrue(offerDuration < Duration.ofSeconds(1).toNanos(), "bounded offers must return promptly");
        release.countDown();
        dispatcher.close();
    }

    @Test
    void fullByteBudgetDropsWithoutExceedingBound() throws Exception {
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BoundedAsyncDispatcher dispatcher = dispatcher(batch -> {
            publishing.countDown();
            release.await();
        }, config(1024, Duration.ofSeconds(1)));
        dispatcher.start();
        assertTrue(dispatcher.submit(TestFixtures.submission(partnerA, 512)));
        assertTrue(publishing.await(1, TimeUnit.SECONDS));

        assertTrue(dispatcher.submit(TestFixtures.submission(partnerA, 512)));
        assertTrue(dispatcher.submit(TestFixtures.submission(partnerA, 512)));
        assertFalse(dispatcher.submit(TestFixtures.submission(partnerA, 512)));

        TelemetryHealthSnapshot saturated = dispatcher.health().snapshot();
        assertEquals(1, saturated.drops().get(DropReason.QUEUE_BYTE_CAPACITY));
        assertEquals(1024, saturated.normalQueueBytes());
        release.countDown();
        dispatcher.close();
    }

    @Test
    void publisherFailureIsContainedAndRetriedOnlyOnce() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        BoundedAsyncDispatcher dispatcher = dispatcher(batch -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("synthetic backend outage");
            }
        }, config(1024L * 1024, Duration.ofSeconds(1)));
        dispatcher.start();

        assertTrue(dispatcher.submit(TestFixtures.submission(partnerA, 256)));
        await(() -> dispatcher.health().snapshot().publishedEvents() == 1, Duration.ofSeconds(2));

        TelemetryHealthSnapshot health = dispatcher.health().snapshot();
        assertEquals(2, attempts.get());
        assertEquals(1, health.publisherFailures());
        assertEquals(0, health.drops().get(DropReason.EXPORT_FAILURE));
        dispatcher.close();
    }

    @Test
    void repeatedPublisherFailureDropsAfterTheSingleRetryAndNeverReachesCaller() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        BoundedAsyncDispatcher dispatcher = dispatcher(batch -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("publisher must be isolated from business caller");
        }, config(1024L * 1024, Duration.ofSeconds(1)));
        dispatcher.start();

        assertTrue(dispatcher.submit(TestFixtures.submission(partnerA, 256)));
        await(() -> dispatcher.health().snapshot().drops().get(DropReason.EXPORT_FAILURE) == 1,
                Duration.ofSeconds(2));

        assertEquals(2, attempts.get());
        assertEquals(2, dispatcher.health().snapshot().publisherFailures());
        dispatcher.close();
    }

    @Test
    void sanitizerOrRecordConstructionExceptionIsRejectedBeforeQueueAdmission() {
        BoundedAsyncDispatcher dispatcher = dispatcher(batch -> {}, config(1024L * 1024, Duration.ofSeconds(1)));
        dispatcher.start();

        assertFalse(dispatcher.submitSafely(() -> {
            throw new IllegalArgumentException("synthetic sanitizer failure");
        }));

        TelemetryHealthSnapshot health = dispatcher.health().snapshot();
        assertEquals(1, health.captureAttempts());
        assertEquals(0, health.enqueued());
        assertEquals(1, health.drops().get(DropReason.MALFORMED));
        dispatcher.close();
    }

    @Test
    void tenMegabyteBase64SourceIsAbsentBeforeQueueAdmissionAndDoesNotConsumeQueueBytes() throws Exception {
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BoundedAsyncDispatcher dispatcher = dispatcher(batch -> {
            publishing.countDown();
            release.await();
        }, config(1024L * 1024, Duration.ofSeconds(1)));
        dispatcher.start();
        assertTrue(dispatcher.submit(TestFixtures.submission(partnerA, 256)));
        assertTrue(publishing.await(1, TimeUnit.SECONDS));

        String source = "A".repeat(10 * 1024 * 1024);
        SanitizationResult sanitized = new FailClosedPayloadSanitizer().sanitize(
                PayloadInput.of(Map.of("opaquePayload", source)),
                PayloadSchema.builder().allow("opaquePayload").build(),
                PayloadCaptureMode.FULL_SANITIZED);
        TelemetrySubmission submission = TestFixtures.submission(
                partnerA, 384, sanitized, PayloadCaptureMode.FULL_SANITIZED, sanitized.status());

        assertEquals(PayloadStatus.BASE64, sanitized.status());
        assertEquals(BinaryKind.UNKNOWN_ENCODED, sanitized.omittedBinary().orElseThrow().kind());
        assertTrue(sanitized.payload().isEmpty());
        assertTrue(sanitized.omittedBinary().orElseThrow().sha256().isEmpty());
        assertTrue(dispatcher.submit(submission));

        TelemetryHealthSnapshot queued = dispatcher.health().snapshot();
        assertEquals(1, queued.normalQueueEvents());
        assertEquals(384, queued.normalQueueBytes());
        assertTrue(source.length() > queued.normalQueueBytes() * 20_000);
        assertTrue(((com.partner.observability.core.model.PartnerEvent) submission.envelope().body())
                .attributes().payload().isEmpty());

        release.countDown();
        dispatcher.close();
    }

    @Test
    void independentEventLogAndGlobalSwitchesRejectRelevantChannels() {
        ObservabilityKillSwitches switches = new ObservabilityKillSwitches(KillSwitchState.allEnabled());
        BoundedAsyncDispatcher dispatcher = new BoundedAsyncDispatcher(
                batch -> {}, config(1024L * 1024, Duration.ofSeconds(1)), switches,
                new TelemetryHealth(SystemTimeSource.INSTANCE));
        dispatcher.start();
        TelemetrySubmission event = TestFixtures.submission(partnerA, 256);
        TelemetrySubmission log = new TelemetrySubmission(
                event.envelope(), event.serializedSizeBytes(), event.priority(), TelemetryChannel.LOG);

        switches.disableLogs();
        assertFalse(dispatcher.submit(log));
        assertTrue(dispatcher.submit(event));
        switches.disableEvents();
        assertFalse(dispatcher.submit(event));
        switches.disableAll();
        assertFalse(dispatcher.submit(event));
        assertTrue(dispatcher.health().snapshot().drops().get(DropReason.DISABLED) >= 3);
        dispatcher.close();
    }

    @Test
    void concurrentProducersPreserveBoundsAndEveryAcceptedRecordIsAccountedFor() throws Exception {
        AtomicInteger published = new AtomicInteger();
        BoundedAsyncDispatcher dispatcher = dispatcher(
                batch -> published.addAndGet(batch.submissions().size()),
                new DispatcherConfig(
                        256, 4L * 1024 * 1024, 8192, 16L * 1024 * 1024, 1024,
                        128, 256 * 1024, Duration.ofMillis(50), Duration.ofMillis(1),
                        Duration.ofSeconds(2), DropPolicy.DROP_NEWEST));
        dispatcher.start();
        int producers = 8;
        int perProducer = 250;
        ExecutorService executor = Executors.newFixedThreadPool(producers);
        CountDownLatch ready = new CountDownLatch(producers);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(producers);
        for (int producer = 0; producer < producers; producer++) {
            executor.execute(() -> {
                ready.countDown();
                try {
                    go.await();
                    for (int index = 0; index < perProducer; index++) {
                        dispatcher.submit(TestFixtures.submission(partnerA, 256));
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(ready.await(1, TimeUnit.SECONDS));
        go.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        executor.shutdownNow();
        await(() -> dispatcher.health().snapshot().publishedEvents() == dispatcher.health().snapshot().enqueued(),
                Duration.ofSeconds(5));

        TelemetryHealthSnapshot health = dispatcher.health().snapshot();
        assertEquals(producers * perProducer, health.captureAttempts());
        assertEquals(health.enqueued(), published.get());
        assertTrue(health.normalQueueEvents() <= 8192);
        assertTrue(health.normalQueueBytes() <= 16L * 1024 * 1024);
        dispatcher.close();
    }

    @Test
    void publisherBatchesNeverMixPartnersEvenWithCollidingApplicationIds() throws Exception {
        PartnerContext partnerB = TestFixtures.context("partner-b", "uk-dev-partner-b", "p002");
        List<PublishBatch> batches = new CopyOnWriteArrayList<>();
        BoundedAsyncDispatcher dispatcher = dispatcher(batches::add, config(1024L * 1024, Duration.ofSeconds(1)));
        dispatcher.start();
        for (int index = 0; index < 20; index++) {
            dispatcher.submit(TestFixtures.submission(index % 2 == 0 ? partnerA : partnerB, 256));
        }
        await(() -> dispatcher.health().snapshot().publishedEvents() == 20, Duration.ofSeconds(2));

        assertTrue(batches.stream().allMatch(batch -> batch.submissions().stream().allMatch(submission ->
                submission.envelope().partnerContext().routingKey().equals(batch.partnerContext().routingKey()))));
        assertTrue(batches.stream().anyMatch(batch -> batch.partnerContext().equals(partnerA)));
        assertTrue(batches.stream().anyMatch(batch -> batch.partnerContext().equals(partnerB)));
        dispatcher.close();
    }

    @Test
    void shutdownReturnsWithinConfiguredBoundWhenPublisherIsStuck() throws Exception {
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BoundedAsyncDispatcher dispatcher = dispatcher(batch -> {
            publishing.countDown();
            release.await();
        }, config(1024L * 1024, Duration.ofMillis(25)));
        dispatcher.start();
        dispatcher.submit(TestFixtures.submission(partnerA, 256));
        assertTrue(publishing.await(1, TimeUnit.SECONDS));
        for (int index = 0; index < 5; index++) {
            dispatcher.submit(TestFixtures.submission(partnerA, 256));
        }

        long start = System.nanoTime();
        dispatcher.close();
        long elapsed = System.nanoTime() - start;

        assertTrue(elapsed < Duration.ofMillis(500).toNanos());
        assertEquals(6, dispatcher.health().snapshot().drops().get(DropReason.SHUTDOWN_TIMEOUT));
        assertEquals(HealthState.DEGRADED, dispatcher.health().snapshot().state());
        release.countDown();
    }

    private BoundedAsyncDispatcher dispatcher(
            com.partner.observability.core.publish.TelemetryPublisher publisher, DispatcherConfig config) {
        return new BoundedAsyncDispatcher(
                publisher,
                config,
                new ObservabilityKillSwitches(KillSwitchState.allEnabled()),
                new TelemetryHealth(SystemTimeSource.INSTANCE));
    }

    private DispatcherConfig config(long normalBytes, Duration shutdown) {
        return new DispatcherConfig(
                64, 64L * 1024, 128, normalBytes, 1024, 1, 1024,
                Duration.ofMillis(50), Duration.ofMillis(1), shutdown, DropPolicy.DROP_NEWEST);
    }

    private void await(Check check, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!check.complete() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(check.complete(), "condition was not met within " + timeout);
    }

    @FunctionalInterface
    private interface Check {
        boolean complete();
    }
}
