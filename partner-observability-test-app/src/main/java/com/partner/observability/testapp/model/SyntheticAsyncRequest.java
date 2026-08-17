package com.partner.observability.testapp.model;

import java.util.Objects;

/** Synthetic asynchronous request accepted by the local mock partner. */
public record SyntheticAsyncRequest(
        String runId,
        SyntheticPartner partner,
        SyntheticAsyncScenario scenario,
        SyntheticCorrelationIdentifiers identifiers) {

    public SyntheticAsyncRequest {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(partner, "partner");
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(identifiers, "identifiers");
    }
}
