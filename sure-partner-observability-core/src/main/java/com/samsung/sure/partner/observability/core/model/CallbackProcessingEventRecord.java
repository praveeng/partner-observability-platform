package com.samsung.sure.partner.observability.core.model;

import com.samsung.sure.partner.observability.core.payload.SanitizationResult;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

public record CallbackProcessingEventRecord(
        String callbackApiId,
        ProcessingMode processingMode,
        ProcessingPhase processingPhase,
        Outcome outcome,
        Optional<String> errorCode,
        OptionalLong durationMs,
        Optional<Boolean> acceptedBeforeCompletion,
        SanitizationResult attributes) implements TelemetryRecord {

    public CallbackProcessingEventRecord {
        callbackApiId = ModelValidation.token(callbackApiId, 63, "callbackApiId");
        Objects.requireNonNull(processingMode, "processingMode");
        Objects.requireNonNull(processingPhase, "processingPhase");
        Objects.requireNonNull(outcome, "outcome");
        errorCode = ModelValidation.optionalToken(errorCode, 64, "errorCode");
        durationMs = durationMs == null ? OptionalLong.empty() : durationMs;
        if (durationMs.isPresent()) {
            ModelValidation.duration(durationMs.getAsLong(), "durationMs");
        }
        acceptedBeforeCompletion = acceptedBeforeCompletion == null ? Optional.empty() : acceptedBeforeCompletion;
        attributes = RecordPayloads.safe(attributes, "attributes");
    }

    @Override
    public TelemetryRecordType recordType() {
        return TelemetryRecordType.CALLBACK_PROCESSING_EVENT;
    }
}
