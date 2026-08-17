package com.partner.observability.core.model;

import com.partner.observability.core.payload.SanitizationResult;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

public record CallbackResponseRecord(
        String callbackApiId,
        OptionalInt httpStatus,
        StatusClass statusClass,
        Outcome outcome,
        long durationMs,
        TransportOutcome transportOutcome,
        Optional<String> errorCode,
        Optional<String> contentType,
        OptionalLong declaredSizeBytes,
        SanitizationResult headers,
        SanitizationResult payload) implements TelemetryRecord {

    public CallbackResponseRecord {
        callbackApiId = ModelValidation.token(callbackApiId, 63, "callbackApiId");
        httpStatus = httpStatus == null ? OptionalInt.empty() : httpStatus;
        httpStatus.ifPresent(ModelValidation::httpStatus);
        Objects.requireNonNull(statusClass, "statusClass");
        Objects.requireNonNull(outcome, "outcome");
        durationMs = ModelValidation.duration(durationMs, "durationMs");
        Objects.requireNonNull(transportOutcome, "transportOutcome");
        errorCode = ModelValidation.optionalToken(errorCode, 64, "errorCode");
        contentType = ModelValidation.contentType(contentType);
        declaredSizeBytes = ModelValidation.size(declaredSizeBytes);
        headers = RecordPayloads.safe(headers, "headers");
        payload = RecordPayloads.safe(payload, "payload");
    }

    @Override
    public TelemetryRecordType recordType() {
        return TelemetryRecordType.CALLBACK_RESPONSE;
    }
}
