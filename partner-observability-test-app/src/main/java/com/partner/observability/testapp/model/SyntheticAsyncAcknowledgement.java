package com.partner.observability.testapp.model;

import java.util.Objects;

/** HTTP 202 acknowledgement returned by the local mock partner. */
public record SyntheticAsyncAcknowledgement(
        String runId,
        String acknowledgement,
        String partnerReferenceId,
        String externalTransactionId) {

    public SyntheticAsyncAcknowledgement {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(acknowledgement, "acknowledgement");
    }

    public SyntheticCorrelationIdentifiers correlationBridge() {
        return new SyntheticCorrelationIdentifiers(
                null, null, null, partnerReferenceId, null, externalTransactionId);
    }
}
