package com.partner.observability.core.model;

public enum AcknowledgementOutcome {
    ACCEPTED,
    REJECTED,
    NO_ACK_TIMEOUT,
    TRANSPORT_FAILURE,
    CANCELLED,
    UNKNOWN
}
