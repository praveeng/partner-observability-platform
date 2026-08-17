package com.partner.observability.autoconfigure;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.partner.observability.core.model.CorrelationIdentifiers;
import com.partner.observability.core.payload.FailClosedPayloadSanitizer;
import com.partner.observability.core.payload.PayloadInput;
import com.partner.observability.core.payload.PayloadSchema;
import com.partner.observability.core.payload.PayloadStatus;
import com.partner.observability.core.payload.SanitizationResult;
import com.partner.observability.core.policy.PayloadCaptureMode;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

final class JacksonSafeBodyCapture implements SafeBodyCapture {

    private final ObjectMapper objectMapper;
    private final FailClosedPayloadSanitizer sanitizer;
    private final List<CorrelationIdentifiersExtractor> extractors;

    JacksonSafeBodyCapture(
            ObjectMapper objectMapper,
            FailClosedPayloadSanitizer sanitizer,
            List<CorrelationIdentifiersExtractor> extractors) {
        this.objectMapper = objectMapper;
        this.sanitizer = sanitizer;
        this.extractors = List.copyOf(extractors);
    }

    @Override
    public CapturedBody capture(
            ObservationDefinition definition,
            ObservationLeg leg,
            Object candidate,
            String contentType,
            OptionalLong declaredSize,
            PayloadCaptureMode mode) {
        try {
            if (candidate instanceof UnavailableBody unavailable) {
                return new CapturedBody(
                        SanitizationResult.omitted(unavailable.status()), CorrelationIdentifiers.empty());
            }
            if (candidate == null) {
                return new CapturedBody(
                        SanitizationResult.omitted(PayloadStatus.NOT_REQUESTED), CorrelationIdentifiers.empty());
            }
            if (declaredSize != null && declaredSize.isPresent() && declaredSize.getAsLong() > MAX_RAW_BYTES) {
                return new CapturedBody(
                        SanitizationResult.omitted(PayloadStatus.OVERSIZE), CorrelationIdentifiers.empty());
            }
            if (candidate instanceof byte[] bytes && bytes.length > MAX_RAW_BYTES
                    || candidate instanceof String text && utf8LengthExceeds(text, MAX_RAW_BYTES)
                    || candidate instanceof JsonNode node && !boundedJsonNode(node)) {
                return new CapturedBody(
                        SanitizationResult.omitted(PayloadStatus.OVERSIZE), CorrelationIdentifiers.empty());
            }
            Object decoded = decode(candidate, contentType);
            if (decoded == null) {
                return SafeBodyCapture.unsupported(mode);
            }
            CorrelationIdentifiers identifiers = configuredIdentifiers(definition.correlation(), decoded);
            for (CorrelationIdentifiersExtractor extractor : extractors) {
                try {
                    identifiers = identifiers.merge(
                            extractor.extract(definition.name(), leg, decoded).orElse(CorrelationIdentifiers.empty()));
                } catch (RuntimeException ignored) {
                    // A failing application extractor reduces observability only.
                }
            }
            if (mode != PayloadCaptureMode.FULL_SANITIZED) {
                return new CapturedBody(
                        SanitizationResult.omitted(PayloadStatus.NOT_REQUESTED), identifiers);
            }
            PayloadSchema.Builder schema = PayloadSchema.builder();
            definition.safeFields().forEach(schema::allow);
            SanitizationResult payload = sanitizer.sanitize(
                    new PayloadInput(decoded, normalizedContentType(contentType), declaredSize), schema.build(), mode);
            return new CapturedBody(payload, identifiers);
        } catch (StackOverflowError exception) {
            return new CapturedBody(
                    SanitizationResult.rejected(PayloadStatus.MALFORMED), CorrelationIdentifiers.empty());
        } catch (Exception exception) {
            return new CapturedBody(
                    SanitizationResult.rejected(PayloadStatus.MALFORMED), CorrelationIdentifiers.empty());
        }
    }

    private Object decode(Object candidate, String contentType) throws java.io.IOException {
        if (candidate instanceof JsonNode node) {
            return objectMapper.convertValue(node, Object.class);
        }
        if (candidate instanceof Map<?, ?> || candidate instanceof List<?>) {
            return candidate;
        }
        if (!isJson(contentType)) {
            return null;
        }
        if (candidate instanceof byte[] bytes) {
            if (bytes.length > MAX_RAW_BYTES) {
                return null;
            }
            return objectMapper.reader()
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .forType(Object.class)
                    .readValue(bytes);
        }
        if (candidate instanceof String text) {
            return objectMapper.reader()
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .forType(Object.class)
                    .readValue(text);
        }
        return null;
    }

    private boolean boundedJsonNode(JsonNode root) {
        ArrayDeque<NodeDepth> pending = new ArrayDeque<>();
        pending.push(new NodeDepth(root, 1));
        int nodes = 0;
        int approximateBytes = 0;
        while (!pending.isEmpty()) {
            NodeDepth current = pending.pop();
            if (++nodes > 128 || current.depth() > 8) return false;
            JsonNode node = current.node();
            approximateBytes += 2;
            if (node.isBinary()) return false;
            if (node.isTextual()) {
                String value = node.textValue();
                int valueBytes = jsonStringBytes(value, MAX_RAW_BYTES - approximateBytes);
                if (valueBytes > MAX_RAW_BYTES - approximateBytes) return false;
                approximateBytes += valueBytes;
            } else if (node.isContainerNode()) {
                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    int fieldBytes = jsonStringBytes(field.getKey(), MAX_RAW_BYTES - approximateBytes);
                    if (fieldBytes > MAX_RAW_BYTES - approximateBytes) return false;
                    approximateBytes += fieldBytes + 2;
                    pending.push(new NodeDepth(field.getValue(), current.depth() + 1));
                }
                if (node.isArray()) {
                    if (node.size() > 64) return false;
                    node.elements().forEachRemaining(value -> pending.push(new NodeDepth(value, current.depth() + 1)));
                }
            } else {
                approximateBytes += 32;
            }
            if (approximateBytes > MAX_RAW_BYTES) return false;
        }
        return true;
    }

    private boolean utf8LengthExceeds(String value, int limit) {
        if (limit < 0) return true;
        return utf8Length(value, limit) > limit;
    }

    private int jsonStringBytes(String value, int stopAfter) {
        int bytes = 2;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\' || character == '\b'
                    || character == '\f' || character == '\n' || character == '\r' || character == '\t') {
                bytes += 2;
            } else if (character < 0x20) {
                bytes += 6;
            } else if (character <= 0x7f) {
                bytes++;
            } else if (character <= 0x7ff) {
                bytes += 2;
            } else if (Character.isHighSurrogate(character)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes += 4;
                index++;
            } else {
                bytes += 3;
            }
            if (bytes > stopAfter) return bytes;
        }
        return bytes;
    }

    private int utf8Length(String value, int stopAfter) {
        int bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x7f) bytes++;
            else if (character <= 0x7ff) bytes += 2;
            else if (Character.isHighSurrogate(character)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes += 4;
                index++;
            } else bytes += 3;
            if (bytes > stopAfter) return bytes;
        }
        return bytes;
    }

    private record NodeDepth(JsonNode node, int depth) {}

    private CorrelationIdentifiers configuredIdentifiers(CorrelationPaths paths, Object decoded) {
        return new CorrelationIdentifiers(
                value(decoded, paths.applicationId()),
                value(decoded, paths.loanId()),
                value(decoded, paths.originalCorrelationId()),
                value(decoded, paths.partnerReferenceId()),
                value(decoded, paths.externalTransactionId()),
                value(decoded, paths.callbackReferenceId()),
                value(decoded, paths.requestId()));
    }

    @SuppressWarnings("unchecked")
    private Optional<String> value(Object decoded, String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        Object current = decoded;
        for (String segment : path.substring(2).split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return Optional.empty();
            }
            current = ((Map<String, Object>) map).get(segment);
        }
        return current instanceof String text ? Optional.of(text) : Optional.empty();
    }

    private boolean isJson(String contentType) {
        String normalized = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT);
        int separator = normalized.indexOf(';');
        if (separator >= 0) {
            normalized = normalized.substring(0, separator).trim();
        }
        return normalized.equals("application/json") || normalized.endsWith("+json");
    }

    private Optional<String> normalizedContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return Optional.empty();
        }
        String normalized = contentType.toLowerCase(java.util.Locale.ROOT);
        int separator = normalized.indexOf(';');
        return Optional.of((separator >= 0 ? normalized.substring(0, separator) : normalized).trim());
    }
}
