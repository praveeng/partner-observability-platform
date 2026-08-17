package com.partner.observability.autoconfigure;

import com.partner.observability.core.context.PartnerContext;
import com.partner.observability.core.model.CorrelationIdentifiers;
import com.partner.observability.core.model.TransportSecurity;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class OutboundObservation {
    private final PartnerObservationEngine engine;
    private final ObservationDefinition definition;
    private final UUID interactionId;
    private final Instant startedAt;
    private final long startedNanos;
    private final CorrelationIdentifiers requestIdentifiers;
    private final Optional<TransportSecurity> transportSecurity;
    private final AtomicBoolean terminal = new AtomicBoolean();

    OutboundObservation(
            PartnerObservationEngine engine,
            ObservationDefinition definition,
            UUID interactionId,
            Instant startedAt,
            long startedNanos,
            CorrelationIdentifiers requestIdentifiers,
            Optional<TransportSecurity> transportSecurity) {
        this.engine = engine;
        this.definition = definition;
        this.interactionId = interactionId;
        this.startedAt = startedAt;
        this.startedNanos = startedNanos;
        this.requestIdentifiers = requestIdentifiers;
        this.transportSecurity = transportSecurity;
    }

    public PartnerContext partnerContext() { return definition.partnerContext(); }
    public UUID interactionId() { return interactionId; }

    public void complete(
            int status,
            Object body,
            boolean bodySupported,
            String contentType,
            OptionalLong declaredSize) {
        if (terminal.compareAndSet(false, true)) {
            engine.completeOutbound(
                    definition, interactionId, startedAt, startedNanos, requestIdentifiers,
                    transportSecurity, status, body, bodySupported, contentType, declaredSize);
        }
    }

    public void failed(Throwable failure) {
        if (terminal.compareAndSet(false, true)) {
            engine.failOutbound(
                    definition, interactionId, startedAt, startedNanos, requestIdentifiers,
                    transportSecurity, failure);
        }
    }
}
