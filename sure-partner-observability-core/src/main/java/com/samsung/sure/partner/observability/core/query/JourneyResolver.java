package com.samsung.sure.partner.observability.core.query;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Stateless bounded graph resolver. Tenant identity is fixed when this object is constructed. */
public final class JourneyResolver {
    public static final int MAX_ROUNDS = 3;
    public static final int MAX_IDENTIFIERS = 32;
    public static final int MAX_RECORDS_PER_ROUND = 500;
    public static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    public static final Duration MAX_TIME_RANGE = Duration.ofDays(16);
    public static final Duration DEADLINE = Duration.ofSeconds(10);
    private static final Pattern VALUE = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final String tenantScope;
    private final JourneyRecordSource source;

    public JourneyResolver(String tenantScope, JourneyRecordSource source) {
        if (!VALUE.matcher(Objects.requireNonNull(tenantScope, "tenantScope")).matches()) {
            throw new IllegalArgumentException("tenantScope must be a bounded opaque token");
        }
        this.tenantScope = tenantScope;
        this.source = Objects.requireNonNull(source, "source");
    }

    public JourneyResolution resolve(
            String correlationProfile,
            JourneyIdentifierType seedType,
            String seedValue,
            Instant from,
            Instant to) {
        validate(correlationProfile, seedValue, from, to);
        Instant deadline = Instant.now().plus(DEADLINE);
        Map<Identifier, Boolean> discovered = new LinkedHashMap<>();
        ArrayDeque<Identifier> frontier = new ArrayDeque<>();
        Map<String, JourneyRecord> records = new LinkedHashMap<>();
        Identifier seed = new Identifier(seedType, seedValue);
        discovered.put(seed, seedType.stable());
        frontier.add(seed);
        int projectedBytes = 0;
        int rounds = 0;
        JourneyResolutionStatus terminal = null;

        while (!frontier.isEmpty() && rounds < MAX_ROUNDS && Instant.now().isBefore(deadline)) {
            rounds++;
            int roundRecords = 0;
            ArrayDeque<Identifier> next = new ArrayDeque<>();
            Set<Identifier> roundQueries = new LinkedHashSet<>();
            while (!frontier.isEmpty()) roundQueries.add(frontier.removeFirst());
            for (Identifier query : roundQueries) {
                if (roundRecords >= MAX_RECORDS_PER_ROUND || Instant.now().isAfter(deadline)) {
                    terminal = JourneyResolutionStatus.PARTIAL_LIMIT;
                    break;
                }
                List<JourneyRecord> matches = source.exactQuery(
                        correlationProfile, query.type(), query.value(), from, to,
                        MAX_RECORDS_PER_ROUND - roundRecords, deadline);
                for (JourneyRecord record : matches) {
                    if (!record.correlationProfile().equals(correlationProfile)) continue;
                    if (records.containsKey(record.eventId())) continue;
                    if (conflicts(discovered.keySet(), record.identifiers())) {
                        terminal = JourneyResolutionStatus.CONFLICT;
                        continue;
                    }
                    if (projectedBytes + record.projectedBytes() > MAX_RESPONSE_BYTES) {
                        terminal = JourneyResolutionStatus.PARTIAL_LIMIT;
                        break;
                    }
                    records.put(record.eventId(), record);
                    projectedBytes += record.projectedBytes();
                    roundRecords++;
                    for (Map.Entry<JourneyIdentifierType, String> entry : record.identifiers().entrySet()) {
                        if (!VALUE.matcher(entry.getValue()).matches()) continue;
                        Identifier candidate = new Identifier(entry.getKey(), entry.getValue());
                        if (!discovered.containsKey(candidate) && discovered.size() < MAX_IDENTIFIERS) {
                            discovered.put(candidate, entry.getKey().stable());
                            next.add(candidate);
                        } else if (!discovered.containsKey(candidate)) {
                            terminal = JourneyResolutionStatus.PARTIAL_LIMIT;
                        }
                    }
                }
            }
            frontier = next;
        }
        if (!frontier.isEmpty() && terminal == null) terminal = JourneyResolutionStatus.PARTIAL_LIMIT;
        List<JourneyRecord> ordered = records.values().stream()
                .sorted(Comparator.comparing(JourneyRecord::occurredAt)
                        .thenComparing(JourneyRecord::observedAt)
                        .thenComparing(JourneyRecord::eventId))
                .toList();
        if (terminal == null) {
            terminal = ordered.isEmpty() ? JourneyResolutionStatus.UNRESOLVED
                    : seedType.stable() ? JourneyResolutionStatus.COMPLETE : JourneyResolutionStatus.WEAK_MATCH;
        }
        return new JourneyResolution(
                terminal, tenantScope, correlationProfile, rounds,
                grouped(discovered.keySet()), ordered, projectedBytes);
    }

    private void validate(String profile, String value, Instant from, Instant to) {
        if (!VALUE.matcher(Objects.requireNonNull(profile, "correlationProfile")).matches()
                || !VALUE.matcher(Objects.requireNonNull(value, "seedValue")).matches()) {
            throw new IllegalArgumentException("profile and seed must be bounded tokens");
        }
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.isAfter(to) || Duration.between(from, to).compareTo(MAX_TIME_RANGE) > 0) {
            throw new IllegalArgumentException("journey time range exceeds 16 days");
        }
    }

    private boolean conflicts(Set<Identifier> current, Map<JourneyIdentifierType, String> incoming) {
        for (JourneyIdentifierType type : JourneyIdentifierType.values()) {
            if (!type.singleton() || !incoming.containsKey(type)) continue;
            String candidate = incoming.get(type);
            boolean other = current.stream().anyMatch(value -> value.type() == type && !value.value().equals(candidate));
            if (other) return true;
        }
        return false;
    }

    private Map<JourneyIdentifierType, List<String>> grouped(Set<Identifier> identifiers) {
        EnumMap<JourneyIdentifierType, List<String>> result = new EnumMap<>(JourneyIdentifierType.class);
        for (JourneyIdentifierType type : JourneyIdentifierType.values()) {
            List<String> values = identifiers.stream().filter(value -> value.type() == type)
                    .map(Identifier::value).sorted().toList();
            if (!values.isEmpty()) result.put(type, values);
        }
        return result;
    }

    private record Identifier(JourneyIdentifierType type, String value) {
        private Identifier {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(value, "value");
        }
    }
}
