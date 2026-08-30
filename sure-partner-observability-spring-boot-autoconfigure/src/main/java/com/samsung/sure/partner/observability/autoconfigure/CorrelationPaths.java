package com.samsung.sure.partner.observability.autoconfigure;

public record CorrelationPaths(
        String applicationId,
        String loanId,
        String originalCorrelationId,
        String partnerReferenceId,
        String externalTransactionId,
        String callbackReferenceId,
        String requestId) {

    static CorrelationPaths copyOf(PartnerObservabilityProperties.Correlation source) {
        return new CorrelationPaths(
                source.getApplicationIdPath(), source.getLoanIdPath(), source.getOriginalCorrelationIdPath(),
                source.getPartnerReferenceIdPath(), source.getExternalTransactionIdPath(),
                source.getCallbackReferenceIdPath(), source.getRequestIdPath());
    }
}
