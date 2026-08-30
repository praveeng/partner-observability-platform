package com.samsung.sure.partner.observability.core.payload;

import java.text.Normalizer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Reviewed allowlist of normalized scalar paths. Unknown paths are never captured. */
public final class PayloadSchema {

    private final Map<String, FieldRule> fields;
    private final Map<String, PayloadFieldPolicy> fieldNames;

    private PayloadSchema(Map<String, FieldRule> fields, Map<String, PayloadFieldPolicy> fieldNames) {
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        this.fieldNames = Collections.unmodifiableMap(new LinkedHashMap<>(fieldNames));
    }

    public static Builder builder() {
        return new Builder();
    }

    Optional<PayloadFieldPolicy> policyFor(String normalizedPath) {
        return ruleFor(normalizedPath).map(FieldRule::policy);
    }

    Optional<FieldRule> ruleFor(String normalizedPath) {
        return Optional.ofNullable(fields.get(normalizedPath));
    }

    Optional<PayloadFieldPolicy> policyForFieldName(String normalizedName) {
        return Optional.ofNullable(fieldNames.get(normalizedName));
    }

    boolean hasDescendant(String normalizedPath) {
        String objectPrefix = normalizedPath + ".";
        String arrayPrefix = normalizedPath + "[].";
        return fields.containsKey(normalizedPath + "[]") || fields.keySet().stream()
                .anyMatch(path -> path.startsWith(objectPrefix) || path.startsWith(arrayPrefix));
    }

    boolean isEmpty() {
        return fields.isEmpty();
    }

    static String normalizeKey(String key) {
        String normalized = Normalizer.normalize(key, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[_\\-.\\s]", "");
    }

    static String normalizePath(String path) {
        Objects.requireNonNull(path, "path");
        if (path.isBlank() || path.length() > 512) {
            throw new IllegalArgumentException("payload path is invalid");
        }
        String[] segments = path.split("\\.");
        StringBuilder normalized = new StringBuilder();
        for (String segment : segments) {
            boolean array = segment.endsWith("[]");
            String raw = array ? segment.substring(0, segment.length() - 2) : segment;
            String key = normalizeKey(raw);
            if (key.isEmpty()) {
                throw new IllegalArgumentException("payload path contains an empty key");
            }
            if (!normalized.isEmpty()) {
                normalized.append('.');
            }
            normalized.append(key);
            if (array) {
                normalized.append("[]");
            }
        }
        return normalized.toString();
    }

    public static final class Builder {
        private final Map<String, FieldRule> fields = new LinkedHashMap<>();
        private final Map<String, PayloadFieldPolicy> fieldNames = new LinkedHashMap<>();

        public Builder allow(String path) {
            return allow(path, PayloadValueType.SAFE_SCALAR);
        }

        public Builder allow(String path, PayloadValueType expectedType) {
            return field(path, PayloadFieldPolicy.ALLOW, expectedType);
        }

        public Builder field(String path, PayloadFieldPolicy policy) {
            return field(path, policy, PayloadValueType.SAFE_SCALAR);
        }

        public Builder field(String path, PayloadFieldPolicy policy, PayloadValueType expectedType) {
            if (fields.size() + fieldNames.size() >= PayloadLimits.HARD_MAX_TOTAL_NODES) {
                throw new IllegalArgumentException("payload schema exceeds the field cap");
            }
            String normalized = normalizePath(path);
            FieldRule rule = new FieldRule(
                    Objects.requireNonNull(policy, "policy"),
                    Objects.requireNonNull(expectedType, "expectedType"));
            if (fields.putIfAbsent(normalized, rule) != null) {
                throw new IllegalArgumentException("duplicate payload path");
            }
            return this;
        }

        /** Adds a non-allowing name rule that applies at every registered nested path. */
        public Builder fieldName(String fieldName, PayloadFieldPolicy policy) {
            if (fields.size() + fieldNames.size() >= PayloadLimits.HARD_MAX_TOTAL_NODES) {
                throw new IllegalArgumentException("payload schema exceeds the field cap");
            }
            PayloadFieldPolicy checked = Objects.requireNonNull(policy, "policy");
            if (checked == PayloadFieldPolicy.ALLOW) {
                throw new IllegalArgumentException("field-name rules cannot expand the path allowlist");
            }
            String raw = Objects.requireNonNull(fieldName, "fieldName");
            if (raw.length() > 256) {
                throw new IllegalArgumentException("payload field name is too long");
            }
            String normalized = normalizeKey(raw);
            if (normalized.isEmpty() || fieldNames.putIfAbsent(normalized, checked) != null) {
                throw new IllegalArgumentException("duplicate or empty payload field name");
            }
            return this;
        }

        public PayloadSchema build() {
            return new PayloadSchema(fields, fieldNames);
        }
    }

    record FieldRule(PayloadFieldPolicy policy, PayloadValueType expectedType) {}
}
