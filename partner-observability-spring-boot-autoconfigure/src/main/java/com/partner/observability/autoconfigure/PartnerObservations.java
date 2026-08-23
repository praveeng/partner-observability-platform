package com.partner.observability.autoconfigure;

import com.partner.observability.core.model.CorrelationIdentifiers;
import com.partner.observability.core.payload.FailClosedPayloadSanitizer;
import com.partner.observability.core.payload.PayloadStatus;
import com.partner.observability.core.payload.SanitizationResult;
import com.partner.observability.core.policy.PayloadCaptureMode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Entry point for the small number of integrations whose authorized plaintext is hidden by encryption. */
public final class PartnerObservations {

    static final int MAX_PLAINTEXT_SCHEMAS = 192;
    private static final ThreadLocal<PartnerObservation> CURRENT = new ThreadLocal<>();
    private static final PartnerObservations NOOP = new PartnerObservations();

    private final PartnerObservabilityProperties properties;
    private final ConfiguredObservationRegistry registry;
    private final PartnerObservationEngine engine;
    private final FailClosedPayloadSanitizer sanitizer;
    private final List<PartnerPlaintextSchema<?>> schemas;
    private final boolean noop;

    private PartnerObservations() {
        properties = null;
        registry = null;
        engine = null;
        sanitizer = null;
        schemas = List.of();
        noop = true;
    }

    PartnerObservations(
            PartnerObservabilityProperties properties,
            ConfiguredObservationRegistry registry,
            PartnerObservationEngine engine,
            FailClosedPayloadSanitizer sanitizer,
            List<PartnerPlaintextSchema<?>> schemas) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
        List<PartnerPlaintextSchema<?>> configured = List.copyOf(schemas);
        this.schemas = configured.size() <= MAX_PLAINTEXT_SCHEMAS ? configured : List.of();
        noop = false;
    }

    static PartnerObservations noop() {
        return NOOP;
    }

    /**
     * Opens one configured logical outbound observation. Unknown, disabled, or no-payload APIs
     * return an inert scope; this method never accepts a partner or tenant identifier.
     */
    public PartnerObservation begin(String configuredApiName) {
        try {
            if (noop || !properties.isEnabled() || !properties.isEventsEnabled()
                    || !properties.isExplicitObservationsEnabled()) {
                return PartnerObservation.noop();
            }
            Optional<ObservationDefinition> selected = registry.outboundByName(configuredApiName);
            if (selected.isEmpty() || engine.effectiveMode(selected.get()) == PayloadCaptureMode.NO_PAYLOAD) {
                return PartnerObservation.noop();
            }
            PartnerObservation previous = CURRENT.get();
            PartnerObservation observation = new PartnerObservation(this, engine, selected.get(), previous);
            CURRENT.set(observation);
            return observation;
        } catch (StackOverflowError | LinkageError | RuntimeException exception) {
            return PartnerObservation.noop();
        }
    }

    CapturedBody capture(ObservationDefinition definition, ObservationLeg leg, Object plaintext) {
        PayloadCaptureMode mode = engine.effectiveMode(definition);
        if (plaintext == null || mode != PayloadCaptureMode.FULL_SANITIZED) {
            return new CapturedBody(
                    SanitizationResult.omitted(PayloadStatus.NOT_REQUESTED),
                    CorrelationIdentifiers.empty());
        }
        PartnerPlaintextSchema<?> match = null;
        for (PartnerPlaintextSchema<?> candidate : schemas) {
            if (candidate.apiName().equals(definition.name()) && candidate.leg() == leg
                    && candidate.supports(plaintext)) {
                if (match != null) {
                    return omitted(PayloadStatus.NOT_ALLOWLISTED);
                }
                match = candidate;
            }
        }
        if (match == null) {
            return omitted(PayloadStatus.UNSUPPORTED_INTEGRATION);
        }
        if (!match.isWithin(definition)) {
            return omitted(PayloadStatus.NOT_ALLOWLISTED);
        }
        try {
            return new CapturedBody(
                    PartnerObservationEngine.safeResult(match.sanitize(plaintext, sanitizer, mode)),
                    CorrelationIdentifiers.empty());
        } catch (StackOverflowError | LinkageError | RuntimeException exception) {
            return omitted(PayloadStatus.MALFORMED);
        }
    }

    private CapturedBody omitted(PayloadStatus status) {
        if (status == PayloadStatus.MALFORMED || status == PayloadStatus.OVERSIZE) {
            return new CapturedBody(
                    SanitizationResult.rejected(status), CorrelationIdentifiers.empty());
        }
        return new CapturedBody(SanitizationResult.omitted(status), CorrelationIdentifiers.empty());
    }

    static Optional<PartnerObservation> current(ObservationDefinition definition) {
        PartnerObservation observation = CURRENT.get();
        return observation != null && observation.matches(definition)
                ? Optional.of(observation) : Optional.empty();
    }

    void restore(PartnerObservation current, PartnerObservation previous) {
        if (CURRENT.get() != current) return;
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
