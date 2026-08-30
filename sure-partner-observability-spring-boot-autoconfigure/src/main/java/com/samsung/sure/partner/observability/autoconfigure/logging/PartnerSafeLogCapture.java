package com.samsung.sure.partner.observability.autoconfigure.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.samsung.sure.partner.observability.autoconfigure.ConfiguredObservationRegistry;
import com.samsung.sure.partner.observability.autoconfigure.PartnerObservabilityProperties;
import com.samsung.sure.partner.observability.autoconfigure.PartnerObservationContext;
import com.samsung.sure.partner.observability.core.context.PartnerContext;
import com.samsung.sure.partner.observability.core.dispatch.BoundedAsyncDispatcher;
import com.samsung.sure.partner.observability.core.dispatch.DropReason;
import com.samsung.sure.partner.observability.core.dispatch.TelemetryChannel;
import com.samsung.sure.partner.observability.core.dispatch.TelemetryPriority;
import com.samsung.sure.partner.observability.core.dispatch.TelemetrySubmission;
import com.samsung.sure.partner.observability.core.health.TelemetryHealth;
import com.samsung.sure.partner.observability.core.model.CaptureDecision;
import com.samsung.sure.partner.observability.core.model.InteractionContext;
import com.samsung.sure.partner.observability.core.model.PartnerBusinessEventRecord;
import com.samsung.sure.partner.observability.core.model.ServiceIdentity;
import com.samsung.sure.partner.observability.core.model.Severity;
import com.samsung.sure.partner.observability.core.model.TelemetryEnvelope;
import com.samsung.sure.partner.observability.core.payload.FailClosedPayloadSanitizer;
import com.samsung.sure.partner.observability.core.payload.PayloadInput;
import com.samsung.sure.partner.observability.core.payload.PayloadSchema;
import com.samsung.sure.partner.observability.core.payload.PayloadStatus;
import com.samsung.sure.partner.observability.core.payload.SanitizationDisposition;
import com.samsung.sure.partner.observability.core.payload.SanitizationResult;
import com.samsung.sure.partner.observability.core.policy.KillSwitchState;
import com.samsung.sure.partner.observability.core.policy.ObservabilityKillSwitches;
import com.samsung.sure.partner.observability.core.policy.PayloadCaptureMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.slf4j.Marker;

/**
 * Converts only exact, configured SLF4J statement definitions into already-safe business events.
 * It never calls getFormattedMessage(), reads a throwable proxy, or invokes argument toString().
 */
final class PartnerSafeLogCapture {

    private static final SanitizationResult NO_ATTRIBUTES =
            SanitizationResult.omitted(PayloadStatus.NOT_REQUESTED);

    private final ConfiguredObservationRegistry observationRegistry;
    private final ObservabilityKillSwitches killSwitches;
    private final BoundedAsyncDispatcher dispatcher;
    private final TelemetryHealth health;
    private final FailClosedPayloadSanitizer sanitizer;
    private final ServiceIdentity serviceIdentity;
    private final String policyVersion;
    private final List<Selection> selections;

    PartnerSafeLogCapture(
            PartnerObservabilityProperties properties,
            ConfiguredObservationRegistry observationRegistry,
            ObservabilityKillSwitches killSwitches,
            BoundedAsyncDispatcher dispatcher,
            TelemetryHealth health,
            FailClosedPayloadSanitizer sanitizer) {
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry");
        this.killSwitches = Objects.requireNonNull(killSwitches, "killSwitches");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.health = Objects.requireNonNull(health, "health");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
        serviceIdentity = new ServiceIdentity(properties.getServiceName(), properties.getServiceVersion());
        policyVersion = properties.getPolicyVersion();
        List<Selection> configured = new ArrayList<>(properties.getLogSelections().size());
        properties.getLogSelections().forEach(value -> configured.add(Selection.from(value)));
        selections = List.copyOf(configured);
    }

    void capture(ILoggingEvent event) {
        if (event == null) {
            return;
        }
        KillSwitchState switches = killSwitches.snapshot();
        if (!switches.observabilityEnabled() || !switches.logsEnabled() || !switches.exportEnabled()) {
            return;
        }
        Optional<Selection> matched = selections.stream()
                .filter(selection -> selection.matches(event))
                .findFirst();
        if (matched.isEmpty()) {
            return;
        }
        Optional<PartnerObservationContext.Snapshot> current = PartnerObservationContext.current();
        if (current.isEmpty() || !registered(current.get().partnerContext())) {
            dropped(DropReason.NO_TRUSTED_CONTEXT);
            return;
        }

        Selection selection = matched.get();
        PayloadCaptureMode configuredMode = selection.arguments().isEmpty()
                ? PayloadCaptureMode.METADATA_ONLY : PayloadCaptureMode.FULL_SANITIZED;
        PayloadCaptureMode effectiveMode = switches.payloadsEnabled()
                ? configuredMode : PayloadCaptureMode.METADATA_ONLY;
        SanitizationResult attributes = effectiveMode == PayloadCaptureMode.FULL_SANITIZED
                ? sanitizer.sanitize(
                        PayloadInput.of(selection.project(event.getArgumentArray())),
                        selection.schema(),
                        PayloadCaptureMode.FULL_SANITIZED)
                : NO_ATTRIBUTES;
        if (attributes.disposition() == SanitizationDisposition.REJECTED) {
            dropped(reason(attributes.status()));
            return;
        }

        PartnerObservationContext.Snapshot snapshot = current.get();
        PartnerBusinessEventRecord record = new PartnerBusinessEventRecord(
                selection.category(),
                selection.journeyStage(),
                selection.outcome(),
                Optional.ofNullable(selection.errorCode()),
                Optional.<BigDecimal>empty(),
                Optional.empty(),
                OptionalInt.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                attributes);
        InteractionContext interaction = new InteractionContext(
                snapshot.interactionKind(),
                snapshot.direction(),
                snapshot.interactionId(),
                0,
                snapshot.callbackAttemptId(),
                snapshot.correlationProfileId(),
                snapshot.identifiers(),
                Optional.empty());
        Instant occurredAt = event.getTimeStamp() > 0
                ? Instant.ofEpochMilli(event.getTimeStamp()) : Instant.now();
        TelemetryEnvelope<PartnerBusinessEventRecord> envelope = new TelemetryEnvelope<>(
                TelemetryEnvelope.CURRENT_SCHEMA_VERSION,
                UUID.randomUUID(),
                occurredAt,
                Instant.now(),
                serviceIdentity,
                snapshot.partnerContext(),
                interaction,
                new CaptureDecision(configuredMode, effectiveMode, policyVersion),
                attributes.status(),
                severity(event.getLevel()),
                selection.outcome(),
                record);
        dispatcher.submitSafely(() -> new TelemetrySubmission(
                envelope,
                estimatedSize(attributes),
                TelemetryPriority.NORMAL,
                TelemetryChannel.LOG));
    }

    private boolean registered(PartnerContext context) {
        return observationRegistry.partner(context.canonicalPartnerKey())
                .filter(context::equals)
                .isPresent();
    }

    private void dropped(DropReason reason) {
        try {
            health.captureAttempted();
            health.dropped(reason);
        } catch (RuntimeException ignored) {
            // Health accounting cannot affect application logging.
        }
    }

    private DropReason reason(PayloadStatus status) {
        return switch (status) {
            case BASE64 -> DropReason.BASE64;
            case BINARY -> DropReason.BINARY;
            case OVERSIZE -> DropReason.OVERSIZE;
            case NOT_ALLOWLISTED -> DropReason.NOT_ALLOWLISTED;
            default -> DropReason.MALFORMED;
        };
    }

    private int estimatedSize(SanitizationResult attributes) {
        int payloadBytes = attributes.payload().map(value -> value.jsonUtf8Bytes()).orElse(0);
        return Math.min(64 * 1024, Math.max(512, 1024 + payloadBytes));
    }

    private Severity severity(Level level) {
        if (level != null && level.isGreaterOrEqual(Level.ERROR)) {
            return Severity.ERROR;
        }
        if (level != null && level.isGreaterOrEqual(Level.WARN)) {
            return Severity.WARN;
        }
        return Severity.INFO;
    }

    private record Selection(
            String category,
            String loggerPattern,
            String marker,
            String messageTemplate,
            int minimumLevel,
            String journeyStage,
            com.samsung.sure.partner.observability.core.model.Outcome outcome,
            String errorCode,
            List<Argument> arguments,
            PayloadSchema schema) {

        static Selection from(PartnerObservabilityProperties.LogSelection source) {
            PayloadSchema.Builder schema = PayloadSchema.builder();
            List<Argument> arguments = new ArrayList<>(source.getArguments().size());
            for (PartnerObservabilityProperties.LogArgument value : source.getArguments()) {
                schema.field(value.getName(), value.getPolicy(), value.getType());
                arguments.add(new Argument(value.getIndex(), value.getName()));
            }
            return new Selection(
                    source.getCategory(),
                    source.getLoggerPattern(),
                    source.getMarker(),
                    source.getMessageTemplate(),
                    level(source.getMinimumLevel()),
                    source.getJourneyStage(),
                    source.getOutcome(),
                    source.getErrorCode(),
                    List.copyOf(arguments),
                    schema.build());
        }

        boolean matches(ILoggingEvent event) {
            Level level = event.getLevel();
            if (level == null || level.toInt() < minimumLevel) {
                return false;
            }
            if (!loggerMatches(loggerPattern, event.getLoggerName())) {
                return false;
            }
            if (!messageTemplate.equals(event.getMessage())) {
                return false;
            }
            Marker eventMarker = event.getMarker();
            return marker == null || eventMarker != null && marker.equals(eventMarker.getName());
        }

        Map<String, Object> project(Object[] values) {
            Map<String, Object> projection = new LinkedHashMap<>();
            if (values == null) {
                return projection;
            }
            for (Argument argument : arguments) {
                if (argument.index() < values.length && !(values[argument.index()] instanceof Throwable)) {
                    projection.put(argument.name(), values[argument.index()]);
                }
            }
            return projection;
        }

        private static int level(String configured) {
            return switch (configured) {
                case "TRACE" -> Level.TRACE_INT;
                case "DEBUG" -> Level.DEBUG_INT;
                case "INFO" -> Level.INFO_INT;
                case "WARN" -> Level.WARN_INT;
                case "ERROR" -> Level.ERROR_INT;
                default -> throw new IllegalArgumentException("unsupported configured log level");
            };
        }

        private static boolean loggerMatches(String pattern, String loggerName) {
            if (loggerName == null) {
                return false;
            }
            if (pattern.endsWith(".**")) {
                String prefix = pattern.substring(0, pattern.length() - 3);
                return loggerName.equals(prefix) || loggerName.startsWith(prefix + ".");
            }
            if (pattern.endsWith(".*")) {
                String prefix = pattern.substring(0, pattern.length() - 2);
                if (!loggerName.startsWith(prefix + ".")) {
                    return false;
                }
                return loggerName.indexOf('.', prefix.length() + 1) < 0;
            }
            return pattern.equals(loggerName);
        }
    }

    private record Argument(int index, String name) {}
}
