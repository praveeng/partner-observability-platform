package com.samsung.sure.partner.observability.core.payload;

import com.samsung.sure.partner.observability.core.policy.PayloadCaptureMode;

public interface PayloadSanitizer {
    SanitizationResult sanitize(PayloadInput input, PayloadSchema schema, PayloadCaptureMode mode);

    <T> SanitizationResult sanitizeObject(T source, PayloadObjectSchema<T> schema, PayloadCaptureMode mode);
}
