package com.partner.observability.autoconfigure;

import com.partner.observability.core.context.PartnerContext;
import com.partner.observability.core.model.CorrelationIdentifiers;
import com.partner.observability.core.model.Direction;
import com.partner.observability.core.model.InteractionKind;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;

/** Scoped servlet/executor context with strict restoration; Reactor integrations use Reactor Context. */
public final class PartnerObservationContext {

    public static final String REACTOR_CONTEXT_KEY = PartnerObservationContext.class.getName() + ".snapshot";
    private static final String MDC_SLOT = "partner_observability_slot";
    private static final String MDC_INTERACTION = "partner_observability_interaction_id";
    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();

    private PartnerObservationContext() {}

    public static Optional<Snapshot> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static Scope open(PartnerContext partnerContext, UUID interactionId) {
        return open(new Snapshot(partnerContext, interactionId));
    }

    public static Scope openCallback(
            PartnerContext partnerContext,
            UUID interactionId,
            UUID callbackAttemptId,
            String correlationProfileId) {
        return open(new Snapshot(
                partnerContext, interactionId, InteractionKind.CALLBACK, Direction.INBOUND_FROM_PARTNER,
                Optional.of(callbackAttemptId), correlationProfileId, CorrelationIdentifiers.empty()));
    }

    /** Adds identifiers extracted by callback instrumentation to the matching trusted scope. */
    static void updateCallbackIdentifiers(
            UUID interactionId, UUID callbackAttemptId, CorrelationIdentifiers identifiers) {
        Snapshot current = CURRENT.get();
        if (current == null
                || !current.interactionId().equals(interactionId)
                || current.callbackAttemptId().filter(callbackAttemptId::equals).isEmpty()) {
            return;
        }
        CURRENT.set(new Snapshot(
                current.partnerContext(),
                current.interactionId(),
                current.interactionKind(),
                current.direction(),
                current.callbackAttemptId(),
                current.correlationProfileId(),
                current.identifiers().merge(identifiers)));
    }

    private static Scope open(Snapshot next) {
        Snapshot previous = CURRENT.get();
        String previousSlot = MDC.get(MDC_SLOT);
        String previousInteraction = MDC.get(MDC_INTERACTION);
        CURRENT.set(next);
        MDC.put(MDC_SLOT, next.partnerContext().partnerSlot());
        MDC.put(MDC_INTERACTION, next.interactionId().toString());
        return () -> {
            restore(MDC_SLOT, previousSlot);
            restore(MDC_INTERACTION, previousInteraction);
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        };
    }

    public record Snapshot(
            PartnerContext partnerContext,
            UUID interactionId,
            InteractionKind interactionKind,
            Direction direction,
            Optional<UUID> callbackAttemptId,
            String correlationProfileId,
            CorrelationIdentifiers identifiers) {
        public Snapshot(PartnerContext partnerContext, UUID interactionId) {
            this(
                    partnerContext, interactionId, InteractionKind.SYNC_OUTBOUND,
                    Direction.OUTBOUND_TO_PARTNER, Optional.empty(), "safe-log",
                    CorrelationIdentifiers.empty());
        }

        public Snapshot {
            Objects.requireNonNull(partnerContext, "partnerContext");
            Objects.requireNonNull(interactionId, "interactionId");
            Objects.requireNonNull(interactionKind, "interactionKind");
            Objects.requireNonNull(direction, "direction");
            callbackAttemptId = callbackAttemptId == null ? Optional.empty() : callbackAttemptId;
            Objects.requireNonNull(correlationProfileId, "correlationProfileId");
            Objects.requireNonNull(identifiers, "identifiers");
        }

        public Runnable wrap(Runnable task) {
            Objects.requireNonNull(task, "task");
            return () -> {
                try (Scope ignored = PartnerObservationContext.open(this)) {
                    task.run();
                }
            };
        }
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override void close();
    }

    private static void restore(String key, String value) {
        if (value == null) MDC.remove(key); else MDC.put(key, value);
    }
}
