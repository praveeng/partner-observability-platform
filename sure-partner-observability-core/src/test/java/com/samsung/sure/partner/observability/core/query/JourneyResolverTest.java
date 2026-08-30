package com.samsung.sure.partner.observability.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JourneyResolverTest {
    private static final Instant NOW = Instant.parse("2026-08-30T00:00:00Z");

    @Test
    void resolvesAThreeStageJourneyWithoutAcceptingTenantInput() {
        JourneyRecord request = record("event-1", Map.of(
                JourneyIdentifierType.APPLICATION_ID, "APP-1",
                JourneyIdentifierType.ORIGINAL_CORRELATION_ID, "CORR-1"));
        JourneyRecord acknowledgement = record("event-2", Map.of(
                JourneyIdentifierType.ORIGINAL_CORRELATION_ID, "CORR-1",
                JourneyIdentifierType.PARTNER_REFERENCE_ID, "PARTNER-1"));
        JourneyRecord callback = record("event-3", Map.of(
                JourneyIdentifierType.ORIGINAL_CORRELATION_ID, "CORR-1",
                JourneyIdentifierType.PARTNER_REFERENCE_ID, "PARTNER-1",
                JourneyIdentifierType.CALLBACK_REFERENCE_ID, "CALLBACK-1"));
        JourneyResolver resolver = new JourneyResolver("tenant-alpha", source(List.of(request, acknowledgement, callback)));

        JourneyResolution result = resolver.resolve(
                "SYNTHETIC_ASYNC", JourneyIdentifierType.APPLICATION_ID, "APP-1",
                NOW.minus(16, ChronoUnit.DAYS), NOW);

        assertEquals(JourneyResolutionStatus.COMPLETE, result.status());
        assertEquals("tenant-alpha", result.tenantScope());
        assertEquals(3, result.records().size());
        assertEquals(List.of("CALLBACK-1"), result.identifiers().get(JourneyIdentifierType.CALLBACK_REFERENCE_ID));
    }

    @Test
    void sameIdentifierInSeparateTenantFixedSourcesNeverCrosses() {
        JourneyResolver alpha = new JourneyResolver("tenant-alpha", source(List.of(record("alpha", Map.of(
                JourneyIdentifierType.APPLICATION_ID, "COLLISION",
                JourneyIdentifierType.LOAN_ID, "ALPHA-LOAN")))));
        JourneyResolver beta = new JourneyResolver("tenant-beta", source(List.of(record("beta", Map.of(
                JourneyIdentifierType.APPLICATION_ID, "COLLISION",
                JourneyIdentifierType.LOAN_ID, "BETA-LOAN")))));

        JourneyResolution alphaResult = alpha.resolve("SYNTHETIC_ASYNC", JourneyIdentifierType.APPLICATION_ID,
                "COLLISION", NOW.minus(1, ChronoUnit.DAYS), NOW);
        JourneyResolution betaResult = beta.resolve("SYNTHETIC_ASYNC", JourneyIdentifierType.APPLICATION_ID,
                "COLLISION", NOW.minus(1, ChronoUnit.DAYS), NOW);

        assertEquals(List.of("ALPHA-LOAN"), alphaResult.identifiers().get(JourneyIdentifierType.LOAN_ID));
        assertEquals(List.of("BETA-LOAN"), betaResult.identifiers().get(JourneyIdentifierType.LOAN_ID));
    }

    @Test
    void rejectsTimeRangesBeyondRetentionAndConflictingSingletons() {
        JourneyResolver invalidRange = new JourneyResolver("tenant-alpha", source(List.of()));
        assertThrows(IllegalArgumentException.class, () -> invalidRange.resolve(
                "SYNTHETIC_ASYNC", JourneyIdentifierType.APPLICATION_ID, "APP-1",
                NOW.minus(17, ChronoUnit.DAYS), NOW));

        JourneyResolver conflict = new JourneyResolver("tenant-alpha", source(List.of(
                record("one", Map.of(JourneyIdentifierType.APPLICATION_ID, "APP-1", JourneyIdentifierType.LOAN_ID, "LOAN-1")),
                record("two", Map.of(JourneyIdentifierType.APPLICATION_ID, "APP-1", JourneyIdentifierType.LOAN_ID, "LOAN-2")))));
        assertEquals(JourneyResolutionStatus.CONFLICT, conflict.resolve(
                "SYNTHETIC_ASYNC", JourneyIdentifierType.APPLICATION_ID, "APP-1",
                NOW.minus(1, ChronoUnit.DAYS), NOW).status());
    }

    private JourneyRecordSource source(List<JourneyRecord> records) {
        return (profile, type, value, from, to, limit, deadline) -> records.stream()
                .filter(record -> profile.equals(record.correlationProfile()))
                .filter(record -> value.equals(record.identifiers().get(type)))
                .limit(limit).toList();
    }

    private JourneyRecord record(String event, Map<JourneyIdentifierType, String> identifiers) {
        return new JourneyRecord(event, "SYNTHETIC_ASYNC", NOW, NOW, identifiers, 256);
    }
}
