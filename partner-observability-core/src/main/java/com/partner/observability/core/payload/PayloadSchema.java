package com.partner.observability.core.payload;

import java.text.Normalizer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Reviewed allowlist of normalized scalar paths. Unknown paths are never captured. */
public final class PayloadSchema {

    private final Map<String, PayloadFieldPolicy> fields;

    private PayloadSchema(Map<String, PayloadFieldPolicy> fields) {
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    public static Builder builder() {
        return new Builder();
    }

    Optional<PayloadFieldPolicy> policyFor(String normalizedPath) {
        return Optional.ofNullable(fields.get(normalizedPath));
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

    private static String normalizePath(String path) {
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
        private final Map<String, PayloadFieldPolicy> fields = new LinkedHashMap<>();

        public Builder allow(String path) {
            return field(path, PayloadFieldPolicy.ALLOW);
        }

        public Builder field(String path, PayloadFieldPolicy policy) {
            if (fields.size() >= PayloadLimits.HARD_MAX_TOTAL_NODES) {
                throw new IllegalArgumentException("payload schema exceeds the field cap");
            }
            String normalized = normalizePath(path);
            if (fields.putIfAbsent(normalized, Objects.requireNonNull(policy, "policy")) != null) {
                throw new IllegalArgumentException("duplicate payload path");
            }
            return this;
        }

        public PayloadSchema build() {
            return new PayloadSchema(fields);
        }
    }
}
