package com.samsung.sure.partner.observability.core.query;

import java.time.Instant;
import java.util.List;

/** One already-authenticated, tenant-fixed source. Implementations must never accept tenant input. */
@FunctionalInterface
public interface JourneyRecordSource {
    List<JourneyRecord> exactQuery(
            String correlationProfile,
            JourneyIdentifierType identifierType,
            String identifierValue,
            Instant from,
            Instant to,
            int limit,
            Instant deadline);
}
