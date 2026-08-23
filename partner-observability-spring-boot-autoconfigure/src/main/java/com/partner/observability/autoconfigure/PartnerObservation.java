package com.partner.observability.autoconfigure;

import com.partner.observability.core.context.PartnerContext;
import com.partner.observability.core.model.CorrelationIdentifiers;
import com.partner.observability.core.model.TransportSecurity;
import com.partner.observability.core.payload.PayloadStatus;
import com.partner.observability.core.payload.SanitizationResult;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * One logical encrypted exchange. Only already-safe projections are retained between hook calls;
 * plaintext DTOs, ciphertext, keys, IVs, credentials, and throwables are never fields of this scope.
 */
public final class PartnerObservation implements AutoCloseable {

    private static final PartnerObservation NOOP = new PartnerObservation();
    private static final RuntimeException SAFE_EXPLICIT_FAILURE = new RuntimeException();

    private final PartnerObservations owner;
    private final PartnerObservationEngine engine;
    private final ObservationDefinition definition;
    private final PartnerObservation previous;
    private final UUID interactionId;
    private final Instant startedAt;
    private final boolean noop;

    private CapturedBody request;
    private CapturedBody response;
    private long transportStartedNanos;
    private Optional<TransportSecurity> transportSecurity = Optional.empty();
    private Integer transportStatus;
    private boolean transportStarted;
    private boolean terminal;
    private boolean closed;
    private boolean closing;

    private PartnerObservation() {
        owner = null;
        engine = null;
        definition = null;
        previous = null;
        interactionId = new UUID(0, 0);
        startedAt = Instant.EPOCH;
        noop = true;
    }

    PartnerObservation(
            PartnerObservations owner,
            PartnerObservationEngine engine,
            ObservationDefinition definition,
            PartnerObservation previous) {
        this.owner = owner;
        this.engine = engine;
        this.definition = definition;
        this.previous = previous;
        interactionId = UUID.randomUUID();
        startedAt = Instant.now();
        noop = false;
    }

    static PartnerObservation noop() { return NOOP; }

    /** Immediately extracts and sanitizes the authorized DTO before business encryption. */
    public synchronized void captureRequest(Object authorizedPlaintext) {
        if (noop || closed || transportStarted || request != null) return;
        try {
            request = owner.capture(definition, ObservationLeg.OUTBOUND_REQUEST, authorizedPlaintext);
        } catch (StackOverflowError | LinkageError | RuntimeException ignored) {
            request = omitted(PayloadStatus.MALFORMED);
        }
    }

    /** Immediately extracts and sanitizes the authorized DTO after successful business decryption. */
    public synchronized void captureResponse(Object authorizedPlaintext) {
        if (noop || closed || terminal || response != null) return;
        try {
            ObservationLeg leg =
                    definition.exchangeMode()
                                    == com.partner.observability.core.model.ExchangeMode.ASYNC_INITIATION
                            ? ObservationLeg.ASYNC_ACKNOWLEDGEMENT
                            : ObservationLeg.OUTBOUND_RESPONSE;
            response = owner.capture(definition, leg, authorizedPlaintext);
            completeIfReady();
        } catch (StackOverflowError | LinkageError | RuntimeException ignored) {
            response = omitted(PayloadStatus.MALFORMED);
            completeIfReady();
        }
    }

    /** Completes a non-instrumented transport wrapper without exposing transport implementation details. */
    public synchronized void succeed(int httpStatus) {
        if (noop || closed || terminal) return;
        try {
            startManualTransport();
            transportStatus = httpStatus;
            completeIfReady();
        } catch (StackOverflowError | LinkageError | RuntimeException ignored) {
            // Hook failure cannot alter the business transport result.
        }
    }

    /** Records only a bounded technical failure for a non-instrumented transport wrapper. */
    public synchronized void failed() {
        if (noop || closed || terminal) return;
        try {
            startManualTransport();
            terminal = true;
            engine.failOutbound(
                    definition, interactionId, startedAt, transportStartedNanos, identifiers(request),
                    transportSecurity, SAFE_EXPLICIT_FAILURE);
        } catch (StackOverflowError | LinkageError | RuntimeException ignored) {
            terminal = true;
        }
    }

    synchronized Optional<OutboundObservation> transportStarted(URI uri, int attempt) {
        if (noop || closed || terminal || transportStarted) return Optional.empty();
        try {
            transportStarted = true;
            transportStartedNanos = System.nanoTime();
            transportSecurity = uri != null && "https".equalsIgnoreCase(uri.getScheme())
                    ? Optional.of(TransportSecurity.TLS) : Optional.empty();
            CapturedBody safeRequest = request == null ? omitted(PayloadStatus.NOT_REQUESTED) : request;
            engine.emitExplicitRequest(
                    definition, interactionId, startedAt, safeRequest, transportSecurity, attempt);
            return Optional.of(OutboundObservation.explicit(this));
        } catch (StackOverflowError | LinkageError | RuntimeException ignored) {
            // The existing HTTP call remains authoritative.
            return Optional.empty();
        }
    }

    synchronized void transportCompleted(int status) {
        if (noop || closed || terminal) return;
        try {
            transportStatus = status;
            completeIfReady();
        } catch (StackOverflowError | LinkageError | RuntimeException ignored) {
            // Completion telemetry cannot alter the already-completed HTTP exchange.
        }
    }

    synchronized void transportFailed(Throwable failure) {
        if (noop || closed || terminal) return;
        try {
            terminal = true;
            engine.failOutbound(
                    definition, interactionId, startedAt, transportStartedNanos, identifiers(request),
                    transportSecurity, failure);
        } catch (StackOverflowError | LinkageError | RuntimeException ignored) {
            terminal = true;
        }
    }

    boolean matches(ObservationDefinition candidate) {
        return !noop && definition == candidate;
    }

    PartnerContext partnerContext() { return definition.partnerContext(); }
    UUID interactionId() { return interactionId; }

    private void startManualTransport() {
        if (transportStarted) return;
        transportStarted = true;
        transportStartedNanos = System.nanoTime();
        CapturedBody safeRequest = request == null ? omitted(PayloadStatus.NOT_REQUESTED) : request;
        engine.emitExplicitRequest(
                definition, interactionId, startedAt, safeRequest, transportSecurity, 1);
    }

    private void completeIfReady() {
        if (terminal || transportStatus == null) return;
        if (response == null && !closing) return;
        if (response == null) response = omitted(PayloadStatus.NOT_REQUESTED);
        terminal = true;
        engine.completeExplicitOutbound(
                definition, interactionId, transportStartedNanos, identifiers(request),
                transportSecurity, transportStatus, response);
    }

    private static CorrelationIdentifiers identifiers(CapturedBody body) {
        return body == null ? CorrelationIdentifiers.empty() : body.identifiers();
    }

    private static CapturedBody omitted(PayloadStatus status) {
        if (status == PayloadStatus.MALFORMED || status == PayloadStatus.OVERSIZE) {
            return new CapturedBody(
                    SanitizationResult.rejected(status), CorrelationIdentifiers.empty());
        }
        return new CapturedBody(
                SanitizationResult.omitted(status), CorrelationIdentifiers.empty());
    }

    @Override
    public synchronized void close() {
        if (noop || closed) return;
        closing = true;
        try {
            if (!terminal) {
                if (transportStatus != null) completeIfReady(); else failed();
            }
        } catch (StackOverflowError | LinkageError | RuntimeException ignored) {
            // Closing observability cannot alter business control flow.
        } finally {
            closed = true;
            owner.restore(this, previous);
        }
    }
}
