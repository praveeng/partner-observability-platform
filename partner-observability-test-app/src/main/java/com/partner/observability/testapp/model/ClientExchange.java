package com.partner.observability.testapp.model;

import java.util.Objects;

/** Raw business-client result retained only for immediate fixture assertions and summarization. */
public record ClientExchange(
        String client,
        SyntheticScenario scenario,
        SyntheticPartner partner,
        String applicationId,
        int attempts,
        int httpStatus,
        String responseBody) {

    public ClientExchange {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(partner, "partner");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(responseBody, "responseBody");
    }
}
