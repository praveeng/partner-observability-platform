package com.partner.observability.core.payload;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Reviewed, reflection-free extraction plan for a known DTO type. Only registered paths are read,
 * and extractor results are immediately passed through the normal fail-closed sanitizer.
 */
public final class PayloadObjectSchema<T> {

    private final Class<T> sourceType;
    private final PayloadSchema payloadSchema;
    private final List<Extraction<T>> extractions;

    private PayloadObjectSchema(
            Class<T> sourceType, PayloadSchema payloadSchema, List<Extraction<T>> extractions) {
        this.sourceType = sourceType;
        this.payloadSchema = payloadSchema;
        this.extractions = List.copyOf(extractions);
    }

    public static <T> Builder<T> builder(Class<T> sourceType) {
        return new Builder<>(sourceType);
    }

    PayloadSchema payloadSchema() {
        return payloadSchema;
    }

    Map<String, Object> project(Object source) {
        if (!sourceType.isInstance(source)) {
            throw new IllegalArgumentException("payload object does not match its registered type");
        }
        T typed = sourceType.cast(source);
        Map<String, Object> projection = new LinkedHashMap<>();
        for (Extraction<T> extraction : extractions) {
            insert(projection, extraction.normalizedPath(), extraction.extractor().apply(typed));
        }
        return projection;
    }

    @SuppressWarnings("unchecked")
    private static void insert(Map<String, Object> target, String path, Object value) {
        String[] segments = path.split("\\.");
        Map<String, Object> current = target;
        for (int index = 0; index < segments.length - 1; index++) {
            String segment = segments[index];
            Object existing = current.get(segment);
            if (existing == null) {
                Map<String, Object> child = new LinkedHashMap<>();
                current.put(segment, child);
                current = child;
            } else if (existing instanceof Map<?, ?> map) {
                current = (Map<String, Object>) map;
            } else {
                throw new IllegalArgumentException("payload extraction paths overlap");
            }
        }
        if (current.putIfAbsent(segments[segments.length - 1], value) != null) {
            throw new IllegalArgumentException("payload extraction path is duplicated");
        }
    }

    private record Extraction<T>(String normalizedPath, Function<? super T, ?> extractor) {}

    public static final class Builder<T> {
        private final Class<T> sourceType;
        private final PayloadSchema.Builder payloadSchema = PayloadSchema.builder();
        private final List<Extraction<T>> extractions = new ArrayList<>();
        private final List<String> normalizedPaths = new ArrayList<>();

        private Builder(Class<T> sourceType) {
            this.sourceType = Objects.requireNonNull(sourceType, "sourceType");
        }

        public Builder<T> allowString(String path, Function<? super T, ?> extractor) {
            return field(path, PayloadFieldPolicy.ALLOW, PayloadValueType.STRING, extractor);
        }

        public Builder<T> allowNumber(String path, Function<? super T, ?> extractor) {
            return field(path, PayloadFieldPolicy.ALLOW, PayloadValueType.NUMBER, extractor);
        }

        public Builder<T> allowBoolean(String path, Function<? super T, ?> extractor) {
            return field(path, PayloadFieldPolicy.ALLOW, PayloadValueType.BOOLEAN, extractor);
        }

        public Builder<T> fieldName(String fieldName, PayloadFieldPolicy policy) {
            payloadSchema.fieldName(fieldName, policy);
            return this;
        }

        public Builder<T> field(
                String path,
                PayloadFieldPolicy policy,
                PayloadValueType expectedType,
                Function<? super T, ?> extractor) {
            Objects.requireNonNull(extractor, "extractor");
            String normalizedPath = PayloadSchema.normalizePath(path);
            if (normalizedPath.contains("[]")) {
                throw new IllegalArgumentException("DTO scalar extractors cannot declare array paths");
            }
            for (String existing : normalizedPaths) {
                if (normalizedPath.startsWith(existing + ".") || existing.startsWith(normalizedPath + ".")) {
                    throw new IllegalArgumentException("payload extraction paths overlap");
                }
            }
            payloadSchema.field(path, policy, expectedType);
            normalizedPaths.add(normalizedPath);
            extractions.add(new Extraction<>(normalizedPath, extractor));
            return this;
        }

        public PayloadObjectSchema<T> build() {
            return new PayloadObjectSchema<>(sourceType, payloadSchema.build(), extractions);
        }
    }
}
