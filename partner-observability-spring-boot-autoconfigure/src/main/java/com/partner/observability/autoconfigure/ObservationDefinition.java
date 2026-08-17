package com.partner.observability.autoconfigure;

import com.partner.observability.core.context.PartnerContext;
import com.partner.observability.core.model.ExchangeMode;
import com.partner.observability.core.policy.PayloadCaptureMode;
import java.util.List;
import java.util.Objects;

public record ObservationDefinition(
        String name,
        String path,
        String method,
        PartnerContext partnerContext,
        String correlationProfile,
        PayloadCaptureMode captureMode,
        List<String> safeFields,
        CorrelationPaths correlation,
        ExchangeMode exchangeMode,
        boolean callback,
        boolean processingEventsEnabled,
        String authenticatedPrincipal) {

    public ObservationDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(partnerContext, "partnerContext");
        Objects.requireNonNull(correlationProfile, "correlationProfile");
        Objects.requireNonNull(captureMode, "captureMode");
        safeFields = List.copyOf(safeFields);
        Objects.requireNonNull(correlation, "correlation");
        Objects.requireNonNull(exchangeMode, "exchangeMode");
    }
}
