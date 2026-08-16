package com.partner.observability.core.policy;

public record KillSwitchState(
        boolean observabilityEnabled,
        boolean payloadsEnabled,
        boolean logsEnabled,
        boolean eventsEnabled,
        boolean metricsEnabled,
        boolean exportEnabled) {

    public static KillSwitchState allDisabled() {
        return new KillSwitchState(false, false, false, false, false, false);
    }

    public static KillSwitchState allEnabled() {
        return new KillSwitchState(true, true, true, true, true, true);
    }

    public PayloadCaptureMode effectiveCaptureMode(PayloadCaptureMode configured) {
        if (!observabilityEnabled || !eventsEnabled) {
            return PayloadCaptureMode.NONE;
        }
        return payloadsEnabled ? configured : configured.reduceTo(PayloadCaptureMode.METADATA_ONLY);
    }
}
