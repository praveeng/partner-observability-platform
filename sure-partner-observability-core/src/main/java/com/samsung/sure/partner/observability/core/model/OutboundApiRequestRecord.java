package com.samsung.sure.partner.observability.core.model;

import com.samsung.sure.partner.observability.core.payload.SanitizationResult;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

public record OutboundApiRequestRecord(
        String apiId,
        String routeTemplate,
        ExchangeMode exchangeMode,
        PartnerHttpMethod method,
        int attempt,
        Optional<String> contentType,
        OptionalLong declaredSizeBytes,
        SanitizationResult headers,
        SanitizationResult query,
        SanitizationResult payload,
        TransportState transportState,
        Optional<TransportSecurity> transportSecurity) implements TelemetryRecord {

    public OutboundApiRequestRecord {
        apiId = ModelValidation.token(apiId, 63, "apiId");
        routeTemplate = ModelValidation.routeTemplate(routeTemplate);
        Objects.requireNonNull(exchangeMode, "exchangeMode");
        Objects.requireNonNull(method, "method");
        if (attempt < 1 || attempt > 10) {
            throw new IllegalArgumentException("attempt must be between 1 and 10");
        }
        contentType = ModelValidation.contentType(contentType);
        declaredSizeBytes = ModelValidation.size(declaredSizeBytes);
        headers = RecordPayloads.safe(headers, "headers");
        query = RecordPayloads.safe(query, "query");
        payload = RecordPayloads.safe(payload, "payload");
        Objects.requireNonNull(transportState, "transportState");
        transportSecurity = transportSecurity == null ? Optional.empty() : transportSecurity;
    }

    @Override
    public TelemetryRecordType recordType() {
        return TelemetryRecordType.OUTBOUND_API_REQUEST;
    }
}
