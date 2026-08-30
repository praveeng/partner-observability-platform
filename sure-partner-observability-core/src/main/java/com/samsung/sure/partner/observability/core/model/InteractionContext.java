package com.samsung.sure.partner.observability.core.model;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable identity for one HTTP exchange and its independently carried journey anchors. */
public record InteractionContext(
        InteractionKind interactionKind,
        Direction direction,
        UUID interactionId,
        int eventSequence,
        Optional<UUID> callbackAttemptId,
        String correlationProfileId,
        CorrelationIdentifiers identifiers,
        Optional<TimelineStage> timelineStage) {

    public InteractionContext {
        Objects.requireNonNull(interactionKind, "interactionKind");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(interactionId, "interactionId");
        if (eventSequence < 0) {
            throw new IllegalArgumentException("eventSequence cannot be negative");
        }
        callbackAttemptId = callbackAttemptId == null ? Optional.empty() : callbackAttemptId;
        correlationProfileId = ModelValidation.token(correlationProfileId, 63, "correlationProfileId");
        Objects.requireNonNull(identifiers, "identifiers");
        timelineStage = timelineStage == null ? Optional.empty() : timelineStage;
        if (interactionKind == InteractionKind.CALLBACK) {
            if (direction != Direction.INBOUND_FROM_PARTNER || callbackAttemptId.isEmpty()) {
                throw new IllegalArgumentException("callback interactions require inbound direction and attempt ID");
            }
        } else if (direction != Direction.OUTBOUND_TO_PARTNER || callbackAttemptId.isPresent()) {
            throw new IllegalArgumentException("outbound interactions cannot carry a callback attempt ID");
        }
    }

    public InteractionContext withSequenceAndStage(int sequence, TimelineStage stage) {
        return new InteractionContext(
                interactionKind, direction, interactionId, sequence, callbackAttemptId,
                correlationProfileId, identifiers, Optional.ofNullable(stage));
    }

    public InteractionContext withIdentifiers(CorrelationIdentifiers merged) {
        return new InteractionContext(
                interactionKind, direction, interactionId, eventSequence, callbackAttemptId,
                correlationProfileId, merged, timelineStage);
    }
}
