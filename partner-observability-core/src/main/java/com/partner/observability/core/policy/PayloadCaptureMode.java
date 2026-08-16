package com.partner.observability.core.policy;

public enum PayloadCaptureMode {
    NONE,
    METADATA_ONLY,
    FULL_SANITIZED;

    public PayloadCaptureMode reduceTo(PayloadCaptureMode ceiling) {
        return ordinal() <= ceiling.ordinal() ? this : ceiling;
    }
}
