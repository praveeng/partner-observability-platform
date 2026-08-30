package com.samsung.sure.partner.observability.core.policy;

public enum PayloadCaptureMode {
    NO_PAYLOAD,
    METADATA_ONLY,
    FULL_SANITIZED;

    public PayloadCaptureMode reduceTo(PayloadCaptureMode ceiling) {
        return ordinal() <= ceiling.ordinal() ? this : ceiling;
    }
}
