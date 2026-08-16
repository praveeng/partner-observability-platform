package com.partner.observability.core.model;

import com.partner.observability.core.payload.SanitizationDisposition;
import com.partner.observability.core.payload.SanitizationResult;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

public record PartnerApiRequest(
        String apiId,
        String routeTemplate,
        PartnerHttpMethod method,
        int attempt,
        Optional<String> contentType,
        OptionalLong declaredSizeBytes,
        SanitizationResult headers,
        SanitizationResult query,
        SanitizationResult payload,
        TransactionIdentifiers identifiers) implements TelemetryRecord {

    public PartnerApiRequest {
        apiId = ModelValidation.token(apiId, 63, "apiId");
        routeTemplate = ModelValidation.routeTemplate(routeTemplate);
        Objects.requireNonNull(method, "method");
        if (attempt < 1 || attempt > 10) {
            throw new IllegalArgumentException("attempt must be between 1 and 10");
        }
        contentType = ModelValidation.contentType(contentType);
        declaredSizeBytes = ModelValidation.size(declaredSizeBytes);
        rejectUnsafe(headers, "headers");
        rejectUnsafe(query, "query");
        rejectUnsafe(payload, "payload");
        Objects.requireNonNull(identifiers, "identifiers");
    }

    @Override
    public TelemetryRecordType recordType() {
        return TelemetryRecordType.API_REQUEST;
    }

    private static void rejectUnsafe(SanitizationResult result, String name) {
        Objects.requireNonNull(result, name);
        if (result.disposition() == SanitizationDisposition.REJECTED) {
            throw new IllegalArgumentException(name + " was rejected by sanitization");
        }
    }
}
