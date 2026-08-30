package com.samsung.sure.partner.observability.testapp.model;

/** Bounded response from the fixture control endpoint; it never includes a callback body. */
public record AsyncInitiationSummary(
        String runId,
        String scenario,
        String partner,
        int acknowledgementHttpStatus,
        boolean acknowledgementReceived,
        SyntheticCorrelationIdentifiers identifiers) {}
