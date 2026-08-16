package com.partner.observability.core.model;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

final class ModelValidation {
    private ModelValidation() {}

    static String token(String value, int maximum, String name) {
        Objects.requireNonNull(value, name);
        if (value.length() > maximum || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    static Optional<String> optionalToken(Optional<String> value, int maximum, String name) {
        Optional<String> normalized = value == null ? Optional.empty() : value;
        normalized.ifPresent(item -> token(item, maximum, name));
        return normalized;
    }

    static Optional<String> contentType(Optional<String> value) {
        Optional<String> normalized = value == null ? Optional.empty() : value;
        normalized.ifPresent(item -> {
            if (item.length() > 127 || !item.matches("[a-z0-9.+-]+/[a-z0-9.+-]+")) {
                throw new IllegalArgumentException("contentType is invalid");
            }
        });
        return normalized;
    }

    static OptionalLong size(OptionalLong value) {
        OptionalLong normalized = value == null ? OptionalLong.empty() : value;
        if (normalized.isPresent() && normalized.getAsLong() < 0) {
            throw new IllegalArgumentException("declaredSizeBytes cannot be negative");
        }
        return normalized;
    }

    static String routeTemplate(String value) {
        Objects.requireNonNull(value, "routeTemplate");
        if (value.length() > 256 || !value.startsWith("/") || value.contains("?") || value.contains("#")
                || value.chars().anyMatch(character -> character < 32)) {
            throw new IllegalArgumentException("routeTemplate is invalid");
        }
        return value;
    }
}
