package com.partner.observability.core.payload;

import java.util.Objects;

public final class SanitizedPayload {
    private final SanitizedValue value;
    private final int jsonUtf8Bytes;

    SanitizedPayload(SanitizedValue value, int jsonUtf8Bytes) {
        this.value = Objects.requireNonNull(value, "value");
        if (jsonUtf8Bytes < 1 || jsonUtf8Bytes > PayloadLimits.HARD_MAX_SAFE_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("jsonUtf8Bytes exceeds the safe payload bound");
        }
        this.jsonUtf8Bytes = jsonUtf8Bytes;
    }

    public SanitizedValue value() {
        return value;
    }

    public int jsonUtf8Bytes() {
        return jsonUtf8Bytes;
    }
}
