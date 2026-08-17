package com.partner.observability.autoconfigure;

import com.partner.observability.core.context.PartnerContext;
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
        Snapshot previous = CURRENT.get();
        String previousSlot = MDC.get(MDC_SLOT);
        String previousInteraction = MDC.get(MDC_INTERACTION);
        Snapshot next = new Snapshot(partnerContext, interactionId);
        CURRENT.set(next);
        MDC.put(MDC_SLOT, partnerContext.partnerSlot());
        MDC.put(MDC_INTERACTION, interactionId.toString());
        return () -> {
            restore(MDC_SLOT, previousSlot);
            restore(MDC_INTERACTION, previousInteraction);
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        };
    }

    public record Snapshot(PartnerContext partnerContext, UUID interactionId) {
        public Snapshot {
            Objects.requireNonNull(partnerContext, "partnerContext");
            Objects.requireNonNull(interactionId, "interactionId");
        }

        public Runnable wrap(Runnable task) {
            Objects.requireNonNull(task, "task");
            return () -> {
                try (Scope ignored = PartnerObservationContext.open(partnerContext, interactionId)) {
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
