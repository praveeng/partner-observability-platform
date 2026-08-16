package com.partner.observability.core.payload;

import java.util.Optional;
import java.util.OptionalLong;

/** Candidate metadata supplied by an integration without forcing additional body reads. */
public record PayloadInput(Object value, Optional<String> contentType, OptionalLong declaredSizeBytes) {

    public PayloadInput {
        contentType = contentType == null ? Optional.empty() : contentType.map(PayloadInput::normalizeContentType);
        declaredSizeBytes = declaredSizeBytes == null ? OptionalLong.empty() : declaredSizeBytes;
        if (declaredSizeBytes.isPresent() && declaredSizeBytes.getAsLong() < 0) {
            throw new IllegalArgumentException("declaredSizeBytes cannot be negative");
        }
    }

    public static PayloadInput of(Object value) {
        return new PayloadInput(value, Optional.empty(), OptionalLong.empty());
    }

    public static PayloadInput of(Object value, String contentType, long declaredSizeBytes) {
        return new PayloadInput(value, Optional.ofNullable(contentType), OptionalLong.of(declaredSizeBytes));
    }

    /** Records parser failure without retaining the malformed source. */
    public static PayloadInput malformed(String contentType, long declaredSizeBytes) {
        return new PayloadInput(Marker.MALFORMED, Optional.ofNullable(contentType), OptionalLong.of(declaredSizeBytes));
    }

    /** Records ciphertext observed at a transport boundary without retaining it. */
    public static PayloadInput encrypted(String contentType, long declaredSizeBytes) {
        return new PayloadInput(Marker.ENCRYPTED, Optional.ofNullable(contentType), OptionalLong.of(declaredSizeBytes));
    }

    boolean isMalformed() {
        return value == Marker.MALFORMED;
    }

    boolean isEncrypted() {
        return value == Marker.ENCRYPTED;
    }

    private static String normalizeContentType(String value) {
        String normalized = value.toLowerCase(java.util.Locale.ROOT).split(";", 2)[0].trim();
        if (normalized.length() > 127 || !normalized.matches("[a-z0-9.+-]+/[a-z0-9.+-]+")) {
            throw new IllegalArgumentException("contentType is invalid");
        }
        return normalized;
    }

    private enum Marker {
        MALFORMED,
        ENCRYPTED
    }
}
