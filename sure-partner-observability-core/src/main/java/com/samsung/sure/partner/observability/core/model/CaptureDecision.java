package com.samsung.sure.partner.observability.core.model;

import com.samsung.sure.partner.observability.core.policy.PayloadCaptureMode;
import java.util.Objects;

public record CaptureDecision(
        PayloadCaptureMode configuredMode, PayloadCaptureMode effectiveMode, String policyVersion) {

    public CaptureDecision {
        Objects.requireNonNull(configuredMode, "configuredMode");
        Objects.requireNonNull(effectiveMode, "effectiveMode");
        if (effectiveMode.ordinal() > configuredMode.ordinal()) {
            throw new IllegalArgumentException("effective capture mode cannot expand configured capture");
        }
        Objects.requireNonNull(policyVersion, "policyVersion");
        if (!policyVersion.matches("[A-Za-z0-9._-]{1,63}")) {
            throw new IllegalArgumentException("policyVersion is invalid");
        }
    }
}
