package com.partner.observability.testapp.model;

import java.util.regex.Pattern;

/** Typed synthetic correlation fields shared by initiation, acknowledgement, and callbacks. */
public record SyntheticCorrelationIdentifiers(
        String applicationId,
        String loanId,
        String originalCorrelationId,
        String partnerReferenceId,
        String callbackReferenceId,
        String externalTransactionId) {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    public SyntheticCorrelationIdentifiers {
        validate(applicationId);
        validate(loanId);
        validate(originalCorrelationId);
        validate(partnerReferenceId);
        validate(callbackReferenceId);
        validate(externalTransactionId);
    }

    public static SyntheticCorrelationIdentifiers initiation(String runId) {
        String suffix = runId.substring(runId.length() - 12);
        return new SyntheticCorrelationIdentifiers(
                "SYNTHETIC-ASYNC-APPLICATION-COLLISION-0001",
                "SYNTHETIC-LOAN-" + suffix,
                "SYNTHETIC-ORIGINAL-CORRELATION-" + suffix,
                null,
                null,
                null);
    }

    public SyntheticCorrelationIdentifiers merge(SyntheticCorrelationIdentifiers additional) {
        return new SyntheticCorrelationIdentifiers(
                first(additional.applicationId, applicationId),
                first(additional.loanId, loanId),
                first(additional.originalCorrelationId, originalCorrelationId),
                first(additional.partnerReferenceId, partnerReferenceId),
                first(additional.callbackReferenceId, callbackReferenceId),
                first(additional.externalTransactionId, externalTransactionId));
    }

    private static String first(String preferred, String fallback) {
        return preferred == null ? fallback : preferred;
    }

    private static void validate(String value) {
        if (value != null && !SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("SYNTHETIC_IDENTIFIER_INVALID");
        }
    }
}
