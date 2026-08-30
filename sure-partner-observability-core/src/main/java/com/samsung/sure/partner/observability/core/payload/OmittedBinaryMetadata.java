package com.samsung.sure.partner.observability.core.payload;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Safe omission evidence containing no field name or caller-controlled content. */
public record OmittedBinaryMetadata(
        BinaryKind kind,
        int candidatesOmitted,
        OptionalLong declaredSizeBytes,
        Optional<String> normalizedContentType,
        Optional<String> sha256) {

    public OmittedBinaryMetadata(
            BinaryKind kind,
            int candidatesOmitted,
            OptionalLong declaredSizeBytes,
            Optional<String> normalizedContentType) {
        this(kind, candidatesOmitted, declaredSizeBytes, normalizedContentType, Optional.empty());
    }

    public OmittedBinaryMetadata {
        Objects.requireNonNull(kind, "kind");
        if (candidatesOmitted < 1 || candidatesOmitted > 128) {
            throw new IllegalArgumentException("candidatesOmitted must be between 1 and 128");
        }
        declaredSizeBytes = declaredSizeBytes == null ? OptionalLong.empty() : declaredSizeBytes;
        normalizedContentType = normalizedContentType == null ? Optional.empty() : normalizedContentType;
        sha256 = sha256 == null ? Optional.empty() : sha256;
        normalizedContentType.ifPresent(value -> {
            if (!value.matches("[a-z0-9.+-]+/[a-z0-9.+-]+") || value.length() > 127) {
                throw new IllegalArgumentException("normalizedContentType is invalid");
            }
        });
        sha256.ifPresent(value -> {
            if (!value.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("sha256 is invalid");
            }
        });
    }
}
