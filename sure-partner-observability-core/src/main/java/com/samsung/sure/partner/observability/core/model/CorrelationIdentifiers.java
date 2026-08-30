package com.samsung.sure.partner.observability.core.model;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Validated high-cardinality correlation metadata. These values are never metric or Loki labels. */
public record CorrelationIdentifiers(
        Optional<String> applicationId,
        Optional<String> loanId,
        Optional<String> originalCorrelationId,
        Optional<String> partnerReferenceId,
        Optional<String> externalTransactionId,
        Optional<String> callbackReferenceId,
        Optional<String> requestId) {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    public CorrelationIdentifiers {
        applicationId = validate(applicationId);
        loanId = validate(loanId);
        originalCorrelationId = validate(originalCorrelationId);
        partnerReferenceId = validate(partnerReferenceId);
        externalTransactionId = validate(externalTransactionId);
        callbackReferenceId = validate(callbackReferenceId);
        requestId = validate(requestId);
    }

    public static CorrelationIdentifiers empty() {
        return new CorrelationIdentifiers(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    public CorrelationIdentifiers merge(CorrelationIdentifiers later) {
        if (later == null) {
            return this;
        }
        return new CorrelationIdentifiers(
                later.applicationId().or(() -> applicationId),
                later.loanId().or(() -> loanId),
                later.originalCorrelationId().or(() -> originalCorrelationId),
                later.partnerReferenceId().or(() -> partnerReferenceId),
                later.externalTransactionId().or(() -> externalTransactionId),
                later.callbackReferenceId().or(() -> callbackReferenceId),
                later.requestId().or(() -> requestId));
    }

    static CorrelationIdentifiers fromLegacy(TransactionIdentifiers identifiers) {
        return new CorrelationIdentifiers(
                identifiers.applicationId(),
                identifiers.loanId(),
                identifiers.correlationId(),
                identifiers.partnerReference(),
                Optional.empty(),
                Optional.empty(),
                identifiers.requestId());
    }

    private static Optional<String> validate(Optional<String> candidate) {
        Optional<String> normalized = candidate == null ? Optional.empty() : candidate;
        normalized.ifPresent(value -> {
            String lower = value.toLowerCase(Locale.ROOT);
            if (!IDENTIFIER.matcher(value).matches()
                    || value.indexOf('=') >= 0
                    || lower.contains("token")
                    || lower.contains("secret")
                    || lower.contains("password")
                    || lower.contains("authorization")) {
                throw new IllegalArgumentException("correlation identifier is unsafe");
            }
        });
        return normalized;
    }
}
