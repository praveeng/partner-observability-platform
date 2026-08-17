package com.partner.observability.core.model;

import com.partner.observability.core.payload.SanitizationResult;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

public record AsyncAcknowledgementRecord(
        String apiId,
        OptionalInt httpStatus,
        StatusClass statusClass,
        AcknowledgementOutcome acknowledgementOutcome,
        Outcome outcome,
        long durationMs,
        ProcessingDisposition processingDisposition,
        Optional<String> errorCode,
        Optional<String> contentType,
        OptionalLong declaredSizeBytes,
        SanitizationResult headers,
        SanitizationResult payload) implements TelemetryRecord {

    public AsyncAcknowledgementRecord {
        apiId = ModelValidation.token(apiId, 63, "apiId");
        httpStatus = httpStatus == null ? OptionalInt.empty() : httpStatus;
        httpStatus.ifPresent(ModelValidation::httpStatus);
        Objects.requireNonNull(statusClass, "statusClass");
        Objects.requireNonNull(acknowledgementOutcome, "acknowledgementOutcome");
        Objects.requireNonNull(outcome, "outcome");
        durationMs = ModelValidation.duration(durationMs, "durationMs");
        Objects.requireNonNull(processingDisposition, "processingDisposition");
        errorCode = ModelValidation.optionalToken(errorCode, 64, "errorCode");
        contentType = ModelValidation.contentType(contentType);
        declaredSizeBytes = ModelValidation.size(declaredSizeBytes);
        headers = RecordPayloads.safe(headers, "headers");
        payload = RecordPayloads.safe(payload, "payload");
    }

    @Override
    public TelemetryRecordType recordType() {
        return TelemetryRecordType.ASYNC_ACKNOWLEDGEMENT;
    }
}
