package com.partner.observability.core.payload;

public enum PayloadStatus {
    CAPTURED,
    NOT_REQUESTED,
    DISABLED,
    UNSUPPORTED_CONTENT_TYPE,
    BINARY,
    BASE64,
    OVERSIZE,
    MALFORMED,
    NOT_ALLOWLISTED,
    STREAM_NOT_CONSUMED,
    UNSUPPORTED_INTEGRATION
}
