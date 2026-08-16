package com.partner.observability.core.payload;

import java.util.Objects;
import java.util.Optional;

public final class SanitizationResult {

    private final SanitizationDisposition disposition;
    private final PayloadStatus status;
    private final Optional<SanitizedPayload> payload;
    private final Optional<OmittedBinaryMetadata> omittedBinary;
    private final int removedValues;
    private final int maskedValues;
    private final int omittedValues;

    private SanitizationResult(
            SanitizationDisposition disposition,
            PayloadStatus status,
            Optional<SanitizedPayload> payload,
            Optional<OmittedBinaryMetadata> omittedBinary,
            int removedValues,
            int maskedValues,
            int omittedValues) {
        this.disposition = Objects.requireNonNull(disposition, "disposition");
        this.status = Objects.requireNonNull(status, "status");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.omittedBinary = Objects.requireNonNull(omittedBinary, "omittedBinary");
        if ((disposition == SanitizationDisposition.CAPTURED) != payload.isPresent()) {
            throw new IllegalArgumentException("captured disposition and payload must agree");
        }
        if (removedValues < 0 || maskedValues < 0 || omittedValues < 0) {
            throw new IllegalArgumentException("sanitization counts cannot be negative");
        }
        this.removedValues = removedValues;
        this.maskedValues = maskedValues;
        this.omittedValues = omittedValues;
    }

    static SanitizationResult captured(
            SanitizedPayload payload,
            Optional<OmittedBinaryMetadata> omittedBinary,
            int removed,
            int masked,
            int omitted) {
        return new SanitizationResult(
                SanitizationDisposition.CAPTURED,
                PayloadStatus.CAPTURED,
                Optional.of(payload),
                omittedBinary,
                removed,
                masked,
                omitted);
    }

    public static SanitizationResult omitted(PayloadStatus status) {
        if (status == PayloadStatus.CAPTURED || status == PayloadStatus.MALFORMED) {
            throw new IllegalArgumentException("status is not an omission status");
        }
        return new SanitizationResult(
                SanitizationDisposition.OMITTED, status, Optional.empty(), Optional.empty(), 0, 0, 0);
    }

    static SanitizationResult omitted(PayloadStatus status, OmittedBinaryMetadata binary) {
        return new SanitizationResult(
                SanitizationDisposition.OMITTED,
                status,
                Optional.empty(),
                Optional.of(binary),
                0,
                0,
                binary.candidatesOmitted());
    }

    public static SanitizationResult rejected(PayloadStatus status) {
        if (status != PayloadStatus.MALFORMED && status != PayloadStatus.OVERSIZE) {
            throw new IllegalArgumentException("rejection requires a bounded rejection status");
        }
        return new SanitizationResult(
                SanitizationDisposition.REJECTED, status, Optional.empty(), Optional.empty(), 0, 0, 0);
    }

    public SanitizationDisposition disposition() {
        return disposition;
    }

    public PayloadStatus status() {
        return status;
    }

    public Optional<SanitizedPayload> payload() {
        return payload;
    }

    public Optional<OmittedBinaryMetadata> omittedBinary() {
        return omittedBinary;
    }

    public int removedValues() {
        return removedValues;
    }

    public int maskedValues() {
        return maskedValues;
    }

    public int omittedValues() {
        return omittedValues;
    }

    public boolean recordAllowed() {
        return disposition != SanitizationDisposition.REJECTED;
    }
}
