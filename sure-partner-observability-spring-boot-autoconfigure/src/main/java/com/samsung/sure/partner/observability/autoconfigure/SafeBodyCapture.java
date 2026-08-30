package com.samsung.sure.partner.observability.autoconfigure;

import com.samsung.sure.partner.observability.core.model.CorrelationIdentifiers;
import com.samsung.sure.partner.observability.core.payload.PayloadStatus;
import com.samsung.sure.partner.observability.core.payload.SanitizationResult;
import com.samsung.sure.partner.observability.core.policy.PayloadCaptureMode;
import java.util.OptionalLong;

interface SafeBodyCapture {
    int MAX_RAW_BYTES = 64 * 1024;

    CapturedBody capture(
            ObservationDefinition definition,
            ObservationLeg leg,
            Object candidate,
            String contentType,
            OptionalLong declaredSize,
            PayloadCaptureMode mode);

    static CapturedBody unsupported(PayloadCaptureMode mode) {
        PayloadStatus status = mode == PayloadCaptureMode.FULL_SANITIZED
                ? PayloadStatus.UNSUPPORTED_INTEGRATION
                : PayloadStatus.NOT_REQUESTED;
        return new CapturedBody(SanitizationResult.omitted(status), CorrelationIdentifiers.empty());
    }
}
