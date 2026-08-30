package com.samsung.sure.partner.observability.core.dispatch;

public enum DropReason {
    DISABLED,
    NO_TRUSTED_CONTEXT,
    NOT_ALLOWLISTED,
    BINARY,
    BASE64,
    OVERSIZE,
    MALFORMED,
    RATE_LIMIT,
    QUEUE_EVENT_CAPACITY,
    QUEUE_BYTE_CAPACITY,
    SERIALIZATION,
    EXPORT_FAILURE,
    SHUTDOWN_TIMEOUT
}
