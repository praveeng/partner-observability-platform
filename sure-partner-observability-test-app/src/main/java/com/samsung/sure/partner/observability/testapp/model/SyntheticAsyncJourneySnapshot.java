package com.samsung.sure.partner.observability.testapp.model;

import java.util.List;

/** Immutable control-plane view of a bounded synthetic journey. */
public record SyntheticAsyncJourneySnapshot(
        String runId,
        String scenario,
        String partner,
        int acknowledgementHttpStatus,
        boolean acknowledgementReceived,
        SyntheticCorrelationIdentifiers identifiers,
        List<SyntheticLifecycleEvent> events,
        List<SyntheticCallbackAttempt> callbackAttempts,
        List<SyntheticCallbackDelivery> callbackDeliveries) {}
