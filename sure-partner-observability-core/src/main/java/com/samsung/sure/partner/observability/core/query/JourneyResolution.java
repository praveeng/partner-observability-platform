package com.samsung.sure.partner.observability.core.query;

import java.util.List;
import java.util.Map;

public record JourneyResolution(
        JourneyResolutionStatus status,
        String tenantScope,
        String correlationProfile,
        int rounds,
        Map<JourneyIdentifierType, List<String>> identifiers,
        List<JourneyRecord> records,
        int projectedBytes) {
    public JourneyResolution {
        identifiers = Map.copyOf(identifiers);
        records = List.copyOf(records);
    }
}
