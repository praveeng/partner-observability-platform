package com.samsung.sure.partner.observability.autoconfigure.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.samsung.sure.partner.observability.autoconfigure.ConfiguredObservationRegistry;
import com.samsung.sure.partner.observability.autoconfigure.PartnerObservabilityAutoConfiguration;
import com.samsung.sure.partner.observability.autoconfigure.PartnerObservationContext;
import com.samsung.sure.partner.observability.core.context.PartnerContext;
import com.samsung.sure.partner.observability.core.dispatch.DropReason;
import com.samsung.sure.partner.observability.core.dispatch.TelemetryChannel;
import com.samsung.sure.partner.observability.core.dispatch.TelemetrySubmission;
import com.samsung.sure.partner.observability.core.health.TelemetryHealth;
import com.samsung.sure.partner.observability.core.model.PartnerBusinessEventRecord;
import com.samsung.sure.partner.observability.core.model.TelemetryEnvelope;
import com.samsung.sure.partner.observability.core.payload.PayloadStatus;
import com.samsung.sure.partner.observability.core.publish.PublishBatch;
import com.samsung.sure.partner.observability.core.publish.TelemetryPublisher;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PartnerSafeLogCompatibilityTest {

    private static final String SELECTED_LOGGER = "com.synthetic.partner.service.ExistingService";
    private static final String INTERNAL_LOGGER = "com.synthetic.internal.SecretWorker";
    private static final Duration AWAIT = Duration.ofSeconds(5);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PartnerObservabilityAutoConfiguration.class));

    @Test
    void selectedExistingLogProducesSafeCopyAndKeepsExistingAppenderSemantics() {
        RecordingPublisher publisher = new RecordingPublisher();
        run(publisher, context -> {
            PartnerLogbackBridge bridge = context.getBean(PartnerLogbackBridge.class);
            PartnerContext partner = partner(context, "partner-a");
            LoggerContext isolated = new LoggerContext();
            Logger logger = isolated.getLogger(SELECTED_LOGGER);
            logger.setLevel(Level.INFO);
            logger.setAdditive(false);
            ListAppender<ILoggingEvent> existing = new ListAppender<>();
            existing.setContext(isolated);
            existing.start();
            logger.addAppender(existing);
            logger.addAppender(bridge.appender());

            try (PartnerObservationContext.Scope ignored =
                    PartnerObservationContext.open(partner, UUID.randomUUID())) {
                logger.info("Selected operation {}", "APPROVED_OPERATION");
            }

            assertThat(existing.list).singleElement().satisfies(event -> {
                assertThat(event.getMessage()).isEqualTo("Selected operation {}");
                assertThat(event.getFormattedMessage()).isEqualTo("Selected operation APPROVED_OPERATION");
            });
            TelemetryEnvelope<?> envelope = awaitRecords(publisher, 1).get(0);
            assertThat(envelope.partnerContext()).isEqualTo(partner);
            assertThat(envelope.payloadStatus()).isEqualTo(PayloadStatus.CAPTURED);
            assertThat(envelope.body()).isInstanceOfSatisfying(
                    PartnerBusinessEventRecord.class,
                    event -> {
                        assertThat(event.eventName()).isEqualTo("APPROVED_LOG");
                        assertThat(event.attributes().payload().orElseThrow().value().toJavaValue())
                                .isEqualTo(java.util.Map.of("operation", "APPROVED_OPERATION"));
                    });
            assertThat(publisher.submissions()).allMatch(value -> value.channel() == TelemetryChannel.LOG);
            assertThat(publisher.publishThread()).isEqualTo("partner-observability-dispatcher");
        });
    }

    @Test
    void nonSelectedInternalLogProducesNoCopy() {
        RecordingPublisher publisher = new RecordingPublisher();
        run(publisher, context -> {
            PartnerLogbackBridge bridge = context.getBean(PartnerLogbackBridge.class);
            PartnerContext partner = partner(context, "partner-a");

            try (PartnerObservationContext.Scope ignored =
                    PartnerObservationContext.open(partner, UUID.randomUUID())) {
                bridge.appender().doAppend(event(
                        INTERNAL_LOGGER, Level.INFO, "Selected operation {}",
                        new Object[] {"INTERNAL_ONLY"}, null, null));
                bridge.appender().doAppend(event(
                        SELECTED_LOGGER, Level.INFO, "Unconfigured internal detail {}",
                        new Object[] {"INTERNAL_ONLY"}, null, null));
            }

            assertThat(publisher.records()).isEmpty();
        });
    }

    @Test
    void missingOrForeignPartnerContextProducesNoCopy() {
        PartnerContext foreign = foreignPartner();
        RecordingPublisher publisher = new RecordingPublisher();
        run(publisher, context -> {
            PartnerLogbackBridge bridge = context.getBean(PartnerLogbackBridge.class);
            TelemetryHealth health = context.getBean(TelemetryHealth.class);
            long missingBefore = health.snapshot().drops(DropReason.NO_TRUSTED_CONTEXT);

            bridge.appender().doAppend(event(
                    SELECTED_LOGGER, Level.INFO, "Selected operation {}",
                    new Object[] {"NO_CONTEXT"}, null, null));
            try (PartnerObservationContext.Scope ignored =
                    PartnerObservationContext.open(foreign, UUID.randomUUID())) {
                bridge.appender().doAppend(event(
                        SELECTED_LOGGER, Level.INFO, "Selected operation {}",
                        new Object[] {"FOREIGN_CONTEXT"}, null, null));
            }

            await(() -> health.snapshot().drops(DropReason.NO_TRUSTED_CONTEXT) >= missingBefore + 2);
            assertThat(publisher.records()).isEmpty();
        });
    }

    @Test
    void stackTraceRemainsOnlyOnTheOriginalLogEvent() {
        RecordingPublisher publisher = new RecordingPublisher();
        run(publisher, context -> {
            PartnerLogbackBridge bridge = context.getBean(PartnerLogbackBridge.class);
            PartnerContext partner = partner(context, "partner-a");
            RuntimeException originalFailure = new RuntimeException("SYNTHETIC_STACK_ONLY");
            LoggingEvent original = event(
                    SELECTED_LOGGER,
                    Level.ERROR,
                    "Selected failure {}",
                    new Object[] {"SAFE_FAILURE_CATEGORY"},
                    originalFailure,
                    null);

            try (PartnerObservationContext.Scope ignored =
                    PartnerObservationContext.open(partner, UUID.randomUUID())) {
                bridge.appender().doAppend(original);
            }

            assertThat(original.getThrowableProxy()).isNotNull();
            TelemetryEnvelope<?> envelope = awaitRecords(publisher, 1).get(0);
            assertThat(envelope.body()).isInstanceOfSatisfying(
                    PartnerBusinessEventRecord.class,
                    event -> assertThat(event.attributes().payload().orElseThrow().value().toJavaValue())
                            .isEqualTo(java.util.Map.of("operation", "SAFE_FAILURE_CATEGORY")));
            assertThat(String.valueOf(envelope))
                    .doesNotContain("SYNTHETIC_STACK_ONLY")
                    .doesNotContain("RuntimeException")
                    .doesNotContain("PartnerSafeLogCompatibilityTest");
        });
    }

    @Test
    void exceptionContainingCustomerDataIsNeverCopied() {
        RecordingPublisher publisher = new RecordingPublisher();
        run(publisher, context -> {
            PartnerLogbackBridge bridge = context.getBean(PartnerLogbackBridge.class);
            PartnerContext partner = partner(context, "partner-a");
            RuntimeException originalFailure =
                    new RuntimeException("SYNTHETIC_CUSTOMER_EMAIL@example.invalid");

            try (PartnerObservationContext.Scope ignored =
                    PartnerObservationContext.open(partner, UUID.randomUUID())) {
                bridge.appender().doAppend(event(
                        SELECTED_LOGGER,
                        Level.ERROR,
                        "Selected failure {}",
                        new Object[] {"SAFE_FAILURE_CATEGORY"},
                        originalFailure,
                        null));
            }

            TelemetryEnvelope<?> envelope = awaitRecords(publisher, 1).get(0);
            assertThat(String.valueOf(envelope))
                    .doesNotContain("SYNTHETIC_CUSTOMER_EMAIL")
                    .doesNotContain("example.invalid")
                    .doesNotContain("RuntimeException");
        });
    }

    @Test
    void authorizationEmbeddedInSelectedMessageIsNeverCopied() {
        RecordingPublisher publisher = new RecordingPublisher();
        run(publisher, context -> {
            PartnerLogbackBridge bridge = context.getBean(PartnerLogbackBridge.class);
            PartnerContext partner = partner(context, "partner-a");

            try (PartnerObservationContext.Scope ignored =
                    PartnerObservationContext.open(partner, UUID.randomUUID())) {
                bridge.appender().doAppend(event(
                        SELECTED_LOGGER, Level.INFO, "Authorization embedded {}",
                        new Object[] {"Bearer SYNTHETIC_AUTHORIZATION_VALUE"}, null, null));
            }

            TelemetryEnvelope<?> record = awaitRecords(publisher, 1).get(0);
            assertThat(record.payloadStatus())
                    .isEqualTo(PayloadStatus.NOT_ALLOWLISTED);
            assertThat(((PartnerBusinessEventRecord) record.body()).attributes().payload()).isEmpty();
            assertThat(String.valueOf(record)).doesNotContain("SYNTHETIC_AUTHORIZATION_VALUE");
        });
    }

    @Test
    void largeSelectedLogLineIsOmittedAsAWhole() {
        RecordingPublisher publisher = new RecordingPublisher();
        run(publisher, context -> {
            PartnerLogbackBridge bridge = context.getBean(PartnerLogbackBridge.class);
            PartnerContext partner = partner(context, "partner-a");
            String large = "Large synthetic safe text. ".repeat(3_000);

            try (PartnerObservationContext.Scope ignored =
                    PartnerObservationContext.open(partner, UUID.randomUUID())) {
                bridge.appender().doAppend(event(
                        SELECTED_LOGGER, Level.INFO, "Large selected {}",
                        new Object[] {large}, null, null));
            }

            TelemetryEnvelope<?> record = awaitRecords(publisher, 1).get(0);
            assertThat(record.payloadStatus()).isEqualTo(PayloadStatus.OVERSIZE);
            assertThat(((PartnerBusinessEventRecord) record.body()).attributes().payload()).isEmpty();
            assertThat(String.valueOf(record)).doesNotContain("Large synthetic safe text");
        });
    }

    @Test
    void base64InSelectedLogLineIsNeverCopied() {
        RecordingPublisher publisher = new RecordingPublisher();
        run(publisher, context -> {
            PartnerLogbackBridge bridge = context.getBean(PartnerLogbackBridge.class);
            PartnerContext partner = partner(context, "partner-a");
            String base64 = "QUJD".repeat(16);

            try (PartnerObservationContext.Scope ignored =
                    PartnerObservationContext.open(partner, UUID.randomUUID())) {
                bridge.appender().doAppend(event(
                        SELECTED_LOGGER, Level.INFO, "Document selected {}",
                        new Object[] {base64}, null, null));
            }

            TelemetryEnvelope<?> record = awaitRecords(publisher, 1).get(0);
            assertThat(record.payloadStatus()).isEqualTo(PayloadStatus.BASE64);
            assertThat(((PartnerBusinessEventRecord) record.body()).attributes().payload()).isEmpty();
            assertThat(String.valueOf(record)).doesNotContain(base64);
        });
    }

    @Test
    void firstStageSanitizationMasksSelectedCustomerDataArgument() {
        RecordingPublisher publisher = new RecordingPublisher();
        run(publisher, context -> {
            PartnerLogbackBridge bridge = context.getBean(PartnerLogbackBridge.class);
            PartnerContext partner = partner(context, "partner-a");

            try (PartnerObservationContext.Scope ignored =
                    PartnerObservationContext.open(partner, UUID.randomUUID())) {
                bridge.appender().doAppend(event(
                        SELECTED_LOGGER,
                        Level.INFO,
                        "Customer contact {}",
                        new Object[] {"customer@example.test"},
                        null,
                        null));
            }

            TelemetryEnvelope<?> record = awaitRecords(publisher, 1).get(0);
            assertThat(record.body()).isInstanceOfSatisfying(
                    PartnerBusinessEventRecord.class,
                    event -> assertThat(event.attributes().payload().orElseThrow().value().toJavaValue())
                            .isEqualTo(java.util.Map.of("email", "c***@e***.test")));
            assertThat(String.valueOf(record)).doesNotContain("customer@example.test");
        });
    }

    @Test
    void unsupportedArgumentIsOmittedWithoutCallingToString() {
        RecordingPublisher publisher = new RecordingPublisher();
        run(publisher, context -> {
            PartnerLogbackBridge bridge = context.getBean(PartnerLogbackBridge.class);
            PartnerContext partner = partner(context, "partner-a");

            try (PartnerObservationContext.Scope ignored =
                    PartnerObservationContext.open(partner, UUID.randomUUID())) {
                bridge.appender().doAppend(event(
                        SELECTED_LOGGER,
                        Level.INFO,
                        "Unsupported selected {}",
                        new Object[] {new MustNotRender()},
                        null,
                        null));
            }

            TelemetryEnvelope<?> record = awaitRecords(publisher, 1).get(0);
            assertThat(record.payloadStatus()).isEqualTo(PayloadStatus.NOT_ALLOWLISTED);
            assertThat(((PartnerBusinessEventRecord) record.body()).attributes().payload()).isEmpty();
        });
    }

    @Test
    void multiplePartnersStaySeparatedAndMarkerCanNarrowSelection() {
        RecordingPublisher publisher = new RecordingPublisher();
        run(publisher, context -> {
            PartnerLogbackBridge bridge = context.getBean(PartnerLogbackBridge.class);
            PartnerContext partnerA = partner(context, "partner-a");
            PartnerContext partnerB = partner(context, "partner-b");
            Marker marker = MarkerFactory.getMarker("PARTNER_SAFE");

            try (PartnerObservationContext.Scope ignored =
                    PartnerObservationContext.open(partnerA, UUID.randomUUID())) {
                bridge.appender().doAppend(event(
                        SELECTED_LOGGER, Level.INFO, "Selected operation {}",
                        new Object[] {"PARTNER_A_OPERATION"}, null, null));
                bridge.appender().doAppend(event(
                        SELECTED_LOGGER, Level.INFO, "Marked selected {}",
                        new Object[] {"MARKER_REQUIRED"}, null, null));
            }
            try (PartnerObservationContext.Scope ignored =
                    PartnerObservationContext.open(partnerB, UUID.randomUUID())) {
                bridge.appender().doAppend(event(
                        SELECTED_LOGGER, Level.INFO, "Selected operation {}",
                        new Object[] {"PARTNER_B_OPERATION"}, null, null));
                bridge.appender().doAppend(event(
                        SELECTED_LOGGER, Level.INFO, "Marked selected {}",
                        new Object[] {"MARKED_OPERATION"}, null, marker));
            }

            List<TelemetryEnvelope<?>> records = awaitRecords(publisher, 3);
            assertThat(records.stream()
                    .map(value -> value.partnerContext().canonicalPartnerKey())
                    .collect(java.util.stream.Collectors.toSet()))
                    .isEqualTo(Set.of("partner-a", "partner-b"));
            assertThat(records.stream()
                    .filter(value -> ((PartnerBusinessEventRecord) value.body())
                            .eventName().equals("MARKED_LOG")))
                    .singleElement()
                    .satisfies(value -> assertThat(value.partnerContext()).isEqualTo(partnerB));
            assertThat(publisher.batches()).allSatisfy(batch ->
                    assertThat(batch.submissions()).allMatch(submission ->
                            submission.envelope().partnerContext().equals(batch.partnerContext())));
        });
    }

    @Test
    void disabledOrFailingPartnerObservabilityDoesNotAffectExistingLogbackPath() {
        runner.withPropertyValues("partner-observability.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(PartnerLogbackBridge.class);
            LoggerContext isolated = new LoggerContext();
            Logger logger = isolated.getLogger(SELECTED_LOGGER);
            logger.setLevel(Level.INFO);
            logger.setAdditive(false);
            ListAppender<ILoggingEvent> existing = new ListAppender<>();
            existing.setContext(isolated);
            existing.start();
            logger.addAppender(existing);
            logger.info("Existing CloudWatch route {}", "DISABLED");
            assertThat(existing.list).singleElement().satisfies(event -> {
                assertThat(event.getMessage()).isEqualTo("Existing CloudWatch route {}");
                assertThat(event.getFormattedMessage()).isEqualTo("Existing CloudWatch route DISABLED");
            });
        });

        RecordingPublisher publisher = new RecordingPublisher();
        publisher.fail = true;
        run(publisher, context -> {
            PartnerLogbackBridge bridge = context.getBean(PartnerLogbackBridge.class);
            TelemetryHealth health = context.getBean(TelemetryHealth.class);
            PartnerContext partner = partner(context, "partner-a");
            LoggerContext isolated = new LoggerContext();
            Logger logger = isolated.getLogger(SELECTED_LOGGER);
            logger.setLevel(Level.INFO);
            logger.setAdditive(false);
            ListAppender<ILoggingEvent> existing = new ListAppender<>();
            existing.setContext(isolated);
            existing.start();
            logger.addAppender(existing);
            logger.addAppender(bridge.appender());

            try (PartnerObservationContext.Scope ignored =
                    PartnerObservationContext.open(partner, UUID.randomUUID())) {
                logger.info("Selected operation {}", "PUBLISHER_FAILURE_SAFE");
            }

            assertThat(existing.list).singleElement().satisfies(event -> {
                assertThat(event.getMessage()).isEqualTo("Selected operation {}");
                assertThat(event.getFormattedMessage())
                        .isEqualTo("Selected operation PUBLISHER_FAILURE_SAFE");
            });
            await(() -> health.snapshot().publisherFailures() > 0);
        });
    }

    private PartnerContext foreignPartner() {
        java.util.concurrent.atomic.AtomicReference<PartnerContext> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        runner.withPropertyValues(
                        "partner-observability.enabled=true",
                        "partner-observability.service-name=foreign-fixture-service",
                        "partner-observability.service-version=1.0",
                        "partner-observability.market=synthetic",
                        "partner-observability.partners[0].key=partner-a",
                        "partner-observability.partners[0].tenant-route-id=foreign-tenant-a",
                        "partner-observability.partners[0].slot=p003",
                        "partner-observability.outbound[0].name=foreign-submit",
                        "partner-observability.outbound[0].origin=https://partner-a.example",
                        "partner-observability.outbound[0].path=/foreign",
                        "partner-observability.outbound[0].partner=partner-a")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    result.set(partner(context, "partner-a"));
                });
        return result.get();
    }

    private void run(
            RecordingPublisher publisher,
            java.util.function.Consumer<org.springframework.context.ConfigurableApplicationContext> assertion) {
        runner.withBean(TelemetryPublisher.class, () -> publisher)
                .withPropertyValues(properties())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertion.accept(context);
                });
    }

    private PartnerContext partner(
            org.springframework.context.ApplicationContext context, String key) {
        return context.getBean(ConfiguredObservationRegistry.class).partner(key).orElseThrow();
    }

    private LoggingEvent event(
            String loggerName,
            Level level,
            String template,
            Object[] arguments,
            Throwable throwable,
            Marker marker) {
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger(loggerName);
        LoggingEvent event = new LoggingEvent(
                getClass().getName(), logger, level, template, throwable, arguments);
        event.setMarker(marker);
        return event;
    }

    private List<TelemetryEnvelope<?>> awaitRecords(RecordingPublisher publisher, int count) {
        await(() -> publisher.records().size() >= count);
        return publisher.records();
    }

    private void await(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(AWAIT);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("SYNTHETIC_AWAIT_INTERRUPTED");
            }
        }
        throw new AssertionError("SYNTHETIC_AWAIT_TIMEOUT");
    }

    private String[] properties() {
        List<String> values = new ArrayList<>(List.of(
                "partner-observability.enabled=true",
                "partner-observability.payloads-enabled=true",
                "partner-observability.logs-enabled=true",
                "partner-observability.events-enabled=true",
                "partner-observability.export-enabled=true",
                "partner-observability.service-name=fixture-service",
                "partner-observability.service-version=1.0",
                "partner-observability.market=synthetic",
                "partner-observability.policy-version=log-policy-v1",
                "partner-observability.partners[0].key=partner-a",
                "partner-observability.partners[0].tenant-route-id=tenant-a",
                "partner-observability.partners[0].slot=p001",
                "partner-observability.partners[1].key=partner-b",
                "partner-observability.partners[1].tenant-route-id=tenant-b",
                "partner-observability.partners[1].slot=p002"));
        selection(values, 0, "APPROVED_LOG", "Selected operation {}", "operation", null);
        selection(values, 1, "FAILURE_LOG", "Selected failure {}", "operation", null);
        selection(values, 2, "AUTH_LOG", "Authorization embedded {}", "detail", null);
        selection(values, 3, "LARGE_LOG", "Large selected {}", "detail", null);
        selection(values, 4, "DOCUMENT_LOG", "Document selected {}", "encodedValue", null);
        selection(values, 5, "MARKED_LOG", "Marked selected {}", "operation", "PARTNER_SAFE");
        selection(values, 6, "CUSTOMER_CONTACT_LOG", "Customer contact {}", "email", null);
        selection(values, 7, "UNSUPPORTED_LOG", "Unsupported selected {}", "detail", null);
        return values.toArray(String[]::new);
    }

    private void selection(
            List<String> values,
            int index,
            String category,
            String template,
            String argument,
            String marker) {
        String prefix = "partner-observability.log-selections[" + index + "].";
        values.add(prefix + "category=" + category);
        values.add(prefix + "logger-pattern=com.synthetic.partner.**");
        values.add(prefix + "message-template=" + template);
        values.add(prefix + "minimum-level=INFO");
        values.add(prefix + "journey-stage=LOG_EVENT");
        values.add(prefix + "arguments[0].index=0");
        values.add(prefix + "arguments[0].name=" + argument);
        values.add(prefix + "arguments[0].type=STRING");
        if (marker != null) {
            values.add(prefix + "marker=" + marker);
        }
    }

    private static final class MustNotRender {
        @Override
        public String toString() {
            throw new AssertionError("selected-log compatibility must not render arbitrary arguments");
        }
    }

    private static final class RecordingPublisher implements TelemetryPublisher {
        private static final int MAX_BATCHES = 32;
        private final ArrayDeque<PublishBatch> batches = new ArrayDeque<>(MAX_BATCHES);
        private volatile String publishThread;
        private final AtomicInteger attempts = new AtomicInteger();
        private volatile boolean fail;

        @Override
        public synchronized void publish(PublishBatch batch) {
            publishThread = Thread.currentThread().getName();
            attempts.incrementAndGet();
            if (fail) {
                throw new IllegalStateException("SYNTHETIC_PUBLISHER_FAILURE");
            }
            if (batches.size() == MAX_BATCHES) {
                batches.removeFirst();
            }
            batches.addLast(batch);
        }

        synchronized List<PublishBatch> batches() {
            return List.copyOf(batches);
        }

        synchronized List<TelemetrySubmission> submissions() {
            return batches.stream().flatMap(value -> value.submissions().stream()).toList();
        }

        String publishThread() {
            return publishThread;
        }

        synchronized List<TelemetryEnvelope<?>> records() {
            return submissions().stream().map(TelemetrySubmission::envelope).toList();
        }
    }
}
