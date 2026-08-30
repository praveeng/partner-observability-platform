package com.samsung.sure.partner.observability.testapp.model;

/** Payload-free callback attempt projection retained by the bounded fixture ledger. */
public record SyntheticCallbackAttempt(
        String callbackAttemptId,
        String deliveryClassification,
        SyntheticCorrelationIdentifiers identifiers,
        Integer callbackSequence,
        int requestBytes,
        String payloadCategory,
        String processingOutcome,
        Integer responseStatus,
        String responseTransportOutcome) {}
