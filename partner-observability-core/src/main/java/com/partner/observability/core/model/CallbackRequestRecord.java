package com.partner.observability.core.model;

import com.partner.observability.core.payload.SanitizationResult;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

public record CallbackRequestRecord(
        String callbackApiId,
        String routeTemplate,
        PartnerHttpMethod method,
        DeliveryClassification deliveryClassification,
        Optional<String> contentType,
        OptionalLong declaredSizeBytes,
        SanitizationResult headers,
        SanitizationResult payload,
        ParsingStatus parsingStatus,
        Instant receivedAt) implements TelemetryRecord {

    public CallbackRequestRecord {
        callbackApiId = ModelValidation.token(callbackApiId, 63, "callbackApiId");
        routeTemplate = ModelValidation.routeTemplate(routeTemplate);
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(deliveryClassification, "deliveryClassification");
        contentType = ModelValidation.contentType(contentType);
        declaredSizeBytes = ModelValidation.size(declaredSizeBytes);
        headers = RecordPayloads.safe(headers, "headers");
        payload = RecordPayloads.safe(payload, "payload");
        Objects.requireNonNull(parsingStatus, "parsingStatus");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }

    @Override
    public TelemetryRecordType recordType() {
        return TelemetryRecordType.CALLBACK_REQUEST;
    }
}
