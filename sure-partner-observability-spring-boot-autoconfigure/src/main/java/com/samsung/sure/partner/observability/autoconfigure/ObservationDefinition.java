package com.samsung.sure.partner.observability.autoconfigure;

import com.samsung.sure.partner.observability.core.context.PartnerContext;
import com.samsung.sure.partner.observability.core.model.ExchangeMode;
import com.samsung.sure.partner.observability.core.policy.PayloadCaptureMode;
import java.util.List;
import java.util.Objects;
import java.net.URI;

public record ObservationDefinition(
        String name,
        URI origin,
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
        if (!callback) {
            Objects.requireNonNull(origin, "origin");
        }
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
