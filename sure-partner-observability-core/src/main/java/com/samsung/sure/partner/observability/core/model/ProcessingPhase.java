package com.samsung.sure.partner.observability.core.model;

public enum ProcessingPhase {
    AUTHENTICATION,
    PARSING,
    VALIDATION,
    BUSINESS_PROCESSING,
    DOWNSTREAM_PROCESSING,
    RESPONSE_WRITE,
    UNKNOWN
}
