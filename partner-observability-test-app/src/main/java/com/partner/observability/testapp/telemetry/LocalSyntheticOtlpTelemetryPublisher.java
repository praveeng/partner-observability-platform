package com.partner.observability.testapp.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.partner.observability.core.model.AsyncAcknowledgementRecord;
import com.partner.observability.core.model.CallbackProcessingEventRecord;
import com.partner.observability.core.model.CallbackRequestRecord;
import com.partner.observability.core.model.CallbackResponseRecord;
import com.partner.observability.core.model.CorrelationIdentifiers;
import com.partner.observability.core.model.OutboundApiRequestRecord;
import com.partner.observability.core.model.OutboundApiResponseRecord;
import com.partner.observability.core.model.PartnerBusinessEventRecord;
import com.partner.observability.core.model.TelemetryEnvelope;
import com.partner.observability.core.model.TelemetryRecord;
import com.partner.observability.core.payload.OmittedBinaryMetadata;
import com.partner.observability.core.payload.SanitizationResult;
import com.partner.observability.core.publish.PublishBatch;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * LOCAL_SYNTHETIC-only transport from the SDK's bounded publisher thread to the fixed tenant
 * gateway. It serializes only {@link TelemetryEnvelope} safe-tree values and never accepts a
 * caller-supplied route, tenant header, or credential.
 */
@Component
@ConditionalOnProperty(name = "local-synthetic.otlp.enabled", havingValue = "true")
public final class LocalSyntheticOtlpTelemetryPublisher {
    private static final String ALPHA_KEY = "partner-alpha-fixture";
    private static final String BETA_KEY = "partner-beta-fixture";
    private static final int MAX_EXPORTED_LINE_BYTES = 64 * 1024;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI endpoint;
    private final Map<String, FixedRoute> routes;

    public LocalSyntheticOtlpTelemetryPublisher(
            ObjectMapper objectMapper,
            @Value("${local-synthetic.otlp.endpoint}") URI endpoint,
            @Value("${local-synthetic.otlp.alpha-username}") String alphaUsername,
            @Value("${local-synthetic.otlp.alpha-password}") String alphaPassword,
            @Value("${local-synthetic.otlp.beta-username}") String betaUsername,
            @Value("${local-synthetic.otlp.beta-password}") String betaPassword) {
        this.objectMapper = objectMapper;
        this.endpoint = endpoint;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.routes = Map.of(
                ALPHA_KEY, new FixedRoute(alphaUsername, alphaPassword, ALPHA_KEY),
                BETA_KEY, new FixedRoute(betaUsername, betaPassword, BETA_KEY));
    }

    public void publish(PublishBatch batch) {
        String partnerKey = batch.partnerContext().canonicalPartnerKey();
        FixedRoute route = Optional.ofNullable(routes.get(partnerKey))
                .orElseThrow(() -> new IllegalArgumentException("LOCAL_SYNTHETIC_UNKNOWN_PARTNER_ROUTE"));
        List<Map<String, Object>> records = batch.submissions().stream()
                .map(submission -> logRecord(submission.envelope()))
                .toList();
        Map<String, Object> resource = Map.of("attributes", resourceAttributes(batch.submissions().get(0).envelope()));
        Map<String, Object> scopeLogs = Map.of(
                "scope", Map.of("name", "partner-observability-test-app-local-otlp", "version", "1"),
                "logRecords", records);
        Map<String, Object> requestBody = Map.of(
                "resourceLogs", List.of(Map.of("resource", resource, "scopeLogs", List.of(scopeLogs))));
        byte[] json;
        try {
            json = objectMapper.writeValueAsBytes(requestBody);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("LOCAL_SYNTHETIC_OTLP_SERIALIZATION_FAILED", exception);
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(2))
                .header("Content-Type", "application/json")
                .header("Authorization", route.basicAuthorization())
                .header("X-Partner-Route", route.fixedPartnerRoute())
                .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("LOCAL_SYNTHETIC_OTLP_REJECTED_" + response.statusCode());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LOCAL_SYNTHETIC_OTLP_INTERRUPTED", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("LOCAL_SYNTHETIC_OTLP_UNAVAILABLE", exception);
        }
    }

    private Map<String, Object> logRecord(TelemetryEnvelope<?> envelope) {
        String line = line(envelope, true);
        if (line.getBytes(StandardCharsets.UTF_8).length > MAX_EXPORTED_LINE_BYTES) {
            line = line(envelope, false);
        }
        if (line.getBytes(StandardCharsets.UTF_8).length > MAX_EXPORTED_LINE_BYTES) {
            throw new IllegalStateException("LOCAL_SYNTHETIC_OTLP_LINE_TOO_LARGE");
        }
        return Map.of(
                "timeUnixNano", epochNanos(envelope.occurredAt().getEpochSecond(), envelope.occurredAt().getNano()),
                "observedTimeUnixNano", epochNanos(envelope.observedAt().getEpochSecond(), envelope.observedAt().getNano()),
                "severityText", envelope.severity().name(),
                "body", Map.of("stringValue", line),
                "attributes", logAttributes(envelope));
    }

    private String line(TelemetryEnvelope<?> envelope, boolean includePayload) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("record", displayRecord(envelope));
        line.put("eventId", envelope.eventId().toString());
        line.put("timestamp", envelope.occurredAt().toString());
        line.put("direction", envelope.direction().name());
        line.put("status", envelope.outcome().name());
        line.put("payloadStatus", envelope.payloadStatus().name());
        envelope.interactionContext().timelineStage().ifPresent(value -> line.put("timelineStage", value.name()));
        CorrelationIdentifiers identifiers = envelope.interactionContext().identifiers();
        put(line, "applicationId", identifiers.applicationId());
        put(line, "loanId", identifiers.loanId());
        put(line, "correlationId", identifiers.originalCorrelationId());
        put(line, "partnerReferenceId", identifiers.partnerReferenceId());
        put(line, "callbackReferenceId", identifiers.callbackReferenceId());
        put(line, "externalTransactionId", identifiers.externalTransactionId());
        put(line, "requestId", identifiers.requestId());
        addRecordDetails(line, envelope.body(), includePayload);
        if (!includePayload) {
            line.put("exportOmission", "LINE_SIZE_BOUND");
        }
        try {
            return objectMapper.writeValueAsString(line);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("LOCAL_SYNTHETIC_LINE_SERIALIZATION_FAILED", exception);
        }
    }

    private void addRecordDetails(Map<String, Object> line, TelemetryRecord body, boolean includePayload) {
        if (body instanceof OutboundApiRequestRecord request) {
            line.put("apiName", request.apiId());
            line.put("attempt", request.attempt());
            line.put("retry", request.attempt() > 1);
            line.put("httpMethod", request.method().name());
            if (includePayload) putPayload(line, "requestPayload", request.payload());
        } else if (body instanceof OutboundApiResponseRecord response) {
            line.put("apiName", response.apiId());
            line.put("latencyMs", response.durationMs());
            put(line, "httpStatus", response.httpStatus());
            put(line, "errorCode", response.errorCode());
            if (includePayload) putPayload(line, "responsePayload", response.payload());
        } else if (body instanceof AsyncAcknowledgementRecord acknowledgement) {
            line.put("apiName", acknowledgement.apiId());
            line.put("latencyMs", acknowledgement.durationMs());
            line.put("acknowledgement", acknowledgement.acknowledgementOutcome().name());
            put(line, "httpStatus", acknowledgement.httpStatus());
            put(line, "errorCode", acknowledgement.errorCode());
            if (includePayload) putPayload(line, "responsePayload", acknowledgement.payload());
        } else if (body instanceof CallbackRequestRecord request) {
            line.put("apiName", request.callbackApiId());
            line.put("httpMethod", request.method().name());
            line.put("deliveryClassification", request.deliveryClassification().name());
            line.put("retry", request.deliveryClassification().name().equals("RETRY"));
            if (includePayload) putPayload(line, "callbackPayload", request.payload());
        } else if (body instanceof CallbackProcessingEventRecord event) {
            line.put("apiName", event.callbackApiId());
            line.put("processingMode", event.processingMode().name());
            line.put("processingPhase", event.processingPhase().name());
            put(line, "latencyMs", event.durationMs());
            put(line, "errorCode", event.errorCode());
        } else if (body instanceof CallbackResponseRecord response) {
            line.put("apiName", response.callbackApiId());
            line.put("latencyMs", response.durationMs());
            line.put("transportOutcome", response.transportOutcome().name());
            put(line, "httpStatus", response.httpStatus());
            put(line, "errorCode", response.errorCode());
            if (includePayload) putPayload(line, "responsePayload", response.payload());
        } else if (body instanceof PartnerBusinessEventRecord event) {
            line.put("apiName", "PARTNER_EVENT");
            line.put("eventName", event.eventName());
            line.put("journeyStage", event.journeyStage());
            put(line, "errorCode", event.errorCode());
            if (includePayload) putPayload(line, "eventAttributes", event.attributes());
        }
    }

    private void putPayload(Map<String, Object> line, String key, SanitizationResult result) {
        result.payload().ifPresent(payload -> line.put(key, payload.value().toJavaValue()));
        line.put(key + "Status", result.status().name());
        line.put(key + "RemovedValues", result.removedValues());
        line.put(key + "MaskedValues", result.maskedValues());
        line.put(key + "OmittedValues", result.omittedValues());
        result.omittedBinary().ifPresent(binary -> line.put(key + "BinaryOmission", binaryMetadata(binary)));
    }

    private Map<String, Object> binaryMetadata(OmittedBinaryMetadata binary) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", binary.kind().name());
        metadata.put("candidatesOmitted", binary.candidatesOmitted());
        put(metadata, "declaredSizeBytes", binary.declaredSizeBytes());
        put(metadata, "contentType", binary.normalizedContentType());
        put(metadata, "sha256", binary.sha256());
        return metadata;
    }

    private List<Map<String, Object>> resourceAttributes(TelemetryEnvelope<?> envelope) {
        List<Map<String, Object>> attributes = new ArrayList<>();
        attribute(attributes, "service.name", envelope.service().serviceName());
        attribute(attributes, "service.version", envelope.service().serviceVersion());
        attribute(attributes, "market", envelope.partnerContext().market());
        attribute(attributes, "deployment.environment", envelope.partnerContext().environment().name());
        return attributes;
    }

    private List<Map<String, Object>> logAttributes(TelemetryEnvelope<?> envelope) {
        List<Map<String, Object>> attributes = new ArrayList<>();
        attribute(attributes, "schema.version", envelope.schemaVersion());
        attribute(attributes, "partner.key", envelope.partnerContext().canonicalPartnerKey());
        attribute(attributes, "event.type", envelope.eventType().wireValue());
        attribute(attributes, "event.domain", envelope.eventDomain().name());
        attribute(attributes, "direction", envelope.direction().name());
        attribute(attributes, "outcome", envelope.outcome().name());
        attribute(attributes, "severity", envelope.severity().name());
        attribute(attributes, "event.id", envelope.eventId().toString());
        attribute(attributes, "interaction.id", envelope.interactionId().toString());
        attribute(attributes, "correlation.profile.id", envelope.interactionContext().correlationProfileId());
        attribute(attributes, "api.id", apiId(envelope.body()));
        attribute(attributes, "service.version", envelope.service().serviceVersion());
        envelope.interactionContext().callbackAttemptId()
                .ifPresent(value -> attribute(attributes, "callback.attempt.id", value.toString()));
        envelope.interactionContext().timelineStage()
                .ifPresent(value -> attribute(attributes, "timeline.stage", value.name()));
        CorrelationIdentifiers identifiers = envelope.interactionContext().identifiers();
        attribute(attributes, "application.id", identifiers.applicationId());
        attribute(attributes, "loan.id", identifiers.loanId());
        attribute(attributes, "correlation.id", identifiers.originalCorrelationId());
        attribute(attributes, "original.correlation.id", identifiers.originalCorrelationId());
        attribute(attributes, "request.id", identifiers.requestId());
        attribute(attributes, "partner.reference.id", identifiers.partnerReferenceId());
        attribute(attributes, "callback.reference.id", identifiers.callbackReferenceId());
        attribute(attributes, "external.transaction.id", identifiers.externalTransactionId());
        return attributes;
    }

    private String displayRecord(TelemetryEnvelope<?> envelope) {
        if (envelope.body() instanceof OutboundApiRequestRecord request) {
            return request.exchangeMode().name().equals("ASYNC_INITIATION")
                    ? "ASYNC_REQUEST_SENT"
                    : "PARTNER_API_REQUEST";
        }
        if (envelope.body() instanceof OutboundApiResponseRecord) return "PARTNER_API_RESPONSE";
        if (envelope.body() instanceof PartnerBusinessEventRecord) return "PARTNER_EVENT";
        return envelope.interactionContext().timelineStage()
                .map(Enum::name)
                .orElse(envelope.eventType().name());
    }

    private String apiId(TelemetryRecord body) {
        if (body instanceof OutboundApiRequestRecord record) return record.apiId();
        if (body instanceof OutboundApiResponseRecord record) return record.apiId();
        if (body instanceof AsyncAcknowledgementRecord record) return record.apiId();
        if (body instanceof CallbackRequestRecord record) return record.callbackApiId();
        if (body instanceof CallbackResponseRecord record) return record.callbackApiId();
        if (body instanceof CallbackProcessingEventRecord record) return record.callbackApiId();
        return "PARTNER_EVENT";
    }

    private void attribute(List<Map<String, Object>> attributes, String key, Optional<String> value) {
        value.ifPresent(item -> attribute(attributes, key, item));
    }

    private void attribute(List<Map<String, Object>> attributes, String key, Object value) {
        Map<String, Object> anyValue = value instanceof Number
                ? Map.of("intValue", String.valueOf(value))
                : Map.of("stringValue", String.valueOf(value));
        attributes.add(Map.of("key", key, "value", anyValue));
    }

    private void put(Map<String, Object> target, String key, Optional<String> value) {
        value.ifPresent(item -> target.put(key, item));
    }

    private void put(Map<String, Object> target, String key, OptionalInt value) {
        if (value.isPresent()) target.put(key, value.getAsInt());
    }

    private void put(Map<String, Object> target, String key, OptionalLong value) {
        if (value.isPresent()) target.put(key, value.getAsLong());
    }

    private String epochNanos(long seconds, int nanos) {
        return Long.toString(Math.addExact(Math.multiplyExact(seconds, 1_000_000_000L), nanos));
    }

    private record FixedRoute(String username, String password, String fixedPartnerRoute) {
        private FixedRoute {
            if (username == null || username.isBlank() || password == null || password.isBlank()) {
                throw new IllegalArgumentException("LOCAL_SYNTHETIC_OTLP_CREDENTIAL_REQUIRED");
            }
        }

        private String basicAuthorization() {
            String value = username + ":" + password;
            return "Basic " + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
