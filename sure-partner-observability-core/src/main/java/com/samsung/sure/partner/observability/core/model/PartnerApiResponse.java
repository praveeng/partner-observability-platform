package com.samsung.sure.partner.observability.core.model;

import com.samsung.sure.partner.observability.core.payload.SanitizationDisposition;
import com.samsung.sure.partner.observability.core.payload.SanitizationResult;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

public record PartnerApiResponse(
        String apiId,
        OptionalInt httpStatus,
        StatusClass statusClass,
        Outcome outcome,
        long durationMs,
        Optional<String> errorCode,
        Optional<String> contentType,
        OptionalLong declaredSizeBytes,
        SanitizationResult headers,
        SanitizationResult payload,
        TransactionIdentifiers identifiers) implements TelemetryRecord {

    public PartnerApiResponse {
        apiId = ModelValidation.token(apiId, 63, "apiId");
        httpStatus = httpStatus == null ? OptionalInt.empty() : httpStatus;
        if (httpStatus.isPresent() && (httpStatus.getAsInt() < 100 || httpStatus.getAsInt() > 599)) {
            throw new IllegalArgumentException("httpStatus is invalid");
        }
        Objects.requireNonNull(statusClass, "statusClass");
        Objects.requireNonNull(outcome, "outcome");
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs cannot be negative");
        }
        errorCode = ModelValidation.optionalToken(errorCode, 64, "errorCode");
        contentType = ModelValidation.contentType(contentType);
        declaredSizeBytes = ModelValidation.size(declaredSizeBytes);
        rejectUnsafe(headers, "headers");
        rejectUnsafe(payload, "payload");
        Objects.requireNonNull(identifiers, "identifiers");
    }

    @Override
    public TelemetryRecordType recordType() {
        return TelemetryRecordType.API_RESPONSE;
    }

    private static void rejectUnsafe(SanitizationResult result, String name) {
        Objects.requireNonNull(result, name);
        if (result.disposition() == SanitizationDisposition.REJECTED) {
            throw new IllegalArgumentException(name + " was rejected by sanitization");
        }
    }
}
