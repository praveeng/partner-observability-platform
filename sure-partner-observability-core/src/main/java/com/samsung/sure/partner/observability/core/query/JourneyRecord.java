package com.samsung.sure.partner.observability.core.query;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public record JourneyRecord(
        String eventId,
        String correlationProfile,
        Instant occurredAt,
        Instant observedAt,
        Map<JourneyIdentifierType, String> identifiers,
        int projectedBytes) {
    public JourneyRecord {
        eventId = requireToken(eventId, "eventId");
        correlationProfile = requireToken(correlationProfile, "correlationProfile");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        identifiers = Map.copyOf(new EnumMap<>(Objects.requireNonNull(identifiers, "identifiers")));
        if (projectedBytes < 0 || projectedBytes > JourneyResolver.MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("projectedBytes outside resolver bound");
        }
    }

    private static String requireToken(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(name + " must be a bounded token");
        }
        return value;
    }
}
