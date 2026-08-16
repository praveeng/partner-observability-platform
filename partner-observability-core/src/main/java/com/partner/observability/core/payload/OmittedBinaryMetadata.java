package com.partner.observability.core.payload;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Safe omission evidence containing no field name, content, digest, or caller-controlled text. */
public record OmittedBinaryMetadata(
        BinaryKind kind,
        int candidatesOmitted,
        OptionalLong declaredSizeBytes,
        Optional<String> normalizedContentType) {

    public OmittedBinaryMetadata {
        Objects.requireNonNull(kind, "kind");
        if (candidatesOmitted < 1 || candidatesOmitted > 128) {
            throw new IllegalArgumentException("candidatesOmitted must be between 1 and 128");
        }
        declaredSizeBytes = declaredSizeBytes == null ? OptionalLong.empty() : declaredSizeBytes;
        normalizedContentType = normalizedContentType == null ? Optional.empty() : normalizedContentType;
        normalizedContentType.ifPresent(value -> {
            if (!value.matches("[a-z0-9.+-]+/[a-z0-9.+-]+") || value.length() > 127) {
                throw new IllegalArgumentException("normalizedContentType is invalid");
            }
        });
    }
}
