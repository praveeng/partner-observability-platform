package com.partner.observability.core.payload;

import com.partner.observability.core.policy.PayloadCaptureMode;

public interface PayloadSanitizer {
    SanitizationResult sanitize(PayloadInput input, PayloadSchema schema, PayloadCaptureMode mode);
}
