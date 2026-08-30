package com.samsung.sure.partner.observability.core.model;

import java.util.Optional;
import java.util.regex.Pattern;

/** Searchable high-cardinality metadata. These values must never become normal labels. */
public record TransactionIdentifiers(
        Optional<String> applicationId,
        Optional<String> loanId,
        Optional<String> correlationId,
        Optional<String> requestId,
        Optional<String> partnerReference) {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    public TransactionIdentifiers {
        applicationId = validate(applicationId);
        loanId = validate(loanId);
        correlationId = validate(correlationId);
        requestId = validate(requestId);
        partnerReference = validate(partnerReference);
    }

    public static TransactionIdentifiers empty() {
        return new TransactionIdentifiers(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static Optional<String> validate(Optional<String> value) {
        Optional<String> normalized = value == null ? Optional.empty() : value;
        normalized.ifPresent(identifier -> {
            String lower = identifier.toLowerCase(java.util.Locale.ROOT);
            if (!IDENTIFIER.matcher(identifier).matches()
                    || identifier.contains("=")
                    || lower.contains("token")
                    || lower.contains("secret")
                    || lower.contains("password")) {
                throw new IllegalArgumentException("transaction identifier is unsafe");
            }
        });
        return normalized;
    }
}
