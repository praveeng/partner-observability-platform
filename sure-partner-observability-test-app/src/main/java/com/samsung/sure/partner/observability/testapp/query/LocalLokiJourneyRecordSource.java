package com.samsung.sure.partner.observability.testapp.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samsung.sure.partner.observability.core.query.JourneyIdentifierType;
import com.samsung.sure.partner.observability.core.query.JourneyRecord;
import com.samsung.sure.partner.observability.core.query.JourneyRecordSource;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** LOCAL_SYNTHETIC adapter whose credentials bind every request to exactly one tenant. */
final class LocalLokiJourneyRecordSource implements JourneyRecordSource {
    private final ObjectMapper objectMapper;
    private final HttpClient client;
    private final URI queryEndpoint;
    private final String authorization;
    private final String serviceName;

    LocalLokiJourneyRecordSource(
            ObjectMapper objectMapper,
            URI queryEndpoint,
            String username,
            String password,
            String serviceName) {
        this.objectMapper = objectMapper;
        this.queryEndpoint = queryEndpoint;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
        this.authorization = "Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        if (serviceName == null || !serviceName.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("QUERY_SERVICE_NAME_INVALID");
        }
        this.serviceName = serviceName;
    }

    @Override
    public List<JourneyRecord> exactQuery(
            String correlationProfile,
            JourneyIdentifierType identifierType,
            String identifierValue,
            Instant from,
            Instant to,
            int limit,
            Instant deadline) {
        if (Instant.now().isAfter(deadline)) return List.of();
        String expression = "{service_name=\"" + serviceName + "\"}"
                + " | correlation_profile_id=\"" + correlationProfile + "\""
                + " | " + identifierType.metadataKey() + "=\"" + identifierValue + "\"";
        URI target = URI.create(queryEndpoint + "?query=" + encode(expression)
                + "&start=" + epochNanos(from) + "&end=" + epochNanos(to)
                + "&limit=" + Math.min(limit, 500) + "&direction=forward");
        long remaining = Math.max(1, Duration.between(Instant.now(), deadline).toMillis());
        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(Duration.ofMillis(Math.min(remaining, 9500)))
                .header("Authorization", authorization)
                .GET().build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body().length > 2 * 1024 * 1024) return List.of();
            return records(objectMapper.readTree(response.body()), correlationProfile, limit);
        } catch (IOException exception) {
            return List.of();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    private List<JourneyRecord> records(JsonNode response, String profile, int limit) {
        List<JourneyRecord> values = new ArrayList<>();
        JsonNode streams = response.path("data").path("result");
        if (!streams.isArray()) return List.of();
        for (JsonNode stream : streams) {
            for (JsonNode value : stream.path("values")) {
                if (values.size() >= limit || !value.isArray() || value.size() < 2) return List.copyOf(values);
                JsonNode line;
                try {
                    line = objectMapper.readTree(value.get(1).asText());
                } catch (IOException exception) {
                    continue;
                }
                String eventId = text(line, "eventId");
                if (eventId == null) continue;
                EnumMap<JourneyIdentifierType, String> identifiers = new EnumMap<>(JourneyIdentifierType.class);
                put(identifiers, JourneyIdentifierType.APPLICATION_ID, text(line, "applicationId"));
                put(identifiers, JourneyIdentifierType.LOAN_ID, text(line, "loanId"));
                put(identifiers, JourneyIdentifierType.ORIGINAL_CORRELATION_ID, text(line, "correlationId"));
                put(identifiers, JourneyIdentifierType.PARTNER_REFERENCE_ID, text(line, "partnerReferenceId"));
                put(identifiers, JourneyIdentifierType.EXTERNAL_TRANSACTION_ID, text(line, "externalTransactionId"));
                put(identifiers, JourneyIdentifierType.CALLBACK_REFERENCE_ID, text(line, "callbackReferenceId"));
                put(identifiers, JourneyIdentifierType.REQUEST_ID, text(line, "requestId"));
                Instant timestamp = instant(value.get(0).asText());
                values.add(new JourneyRecord(
                        eventId, profile, timestamp, timestamp, identifiers,
                        Math.min(2 * 1024 * 1024, value.get(1).asText().getBytes(StandardCharsets.UTF_8).length + 256)));
            }
        }
        return List.copyOf(values);
    }

    private void put(Map<JourneyIdentifierType, String> target, JourneyIdentifierType type, String value) {
        if (value != null) target.put(type, value);
    }

    private String text(JsonNode source, String name) {
        JsonNode value = source.get(name);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private Instant instant(String nanos) {
        long value = Long.parseLong(nanos);
        return Instant.ofEpochSecond(value / 1_000_000_000L, value % 1_000_000_000L);
    }

    private String epochNanos(Instant value) {
        return Long.toString(Math.addExact(Math.multiplyExact(value.getEpochSecond(), 1_000_000_000L), value.getNano()));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
