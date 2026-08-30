package com.samsung.sure.partner.observability.core.model;

import com.samsung.sure.partner.observability.core.payload.SanitizationDisposition;
import com.samsung.sure.partner.observability.core.payload.SanitizationResult;
import java.util.Objects;

final class RecordPayloads {
    private RecordPayloads() {}

    static SanitizationResult safe(SanitizationResult result, String name) {
        Objects.requireNonNull(result, name);
        if (result.disposition() == SanitizationDisposition.REJECTED) {
            throw new IllegalArgumentException(name + " was rejected by sanitization");
        }
        return result;
    }

    static boolean captured(SanitizationResult result) {
        return result.disposition() == SanitizationDisposition.CAPTURED;
    }
}
