package com.partner.observability.core.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KillSwitchTest {

    @Test
    void switchesIndependentlyAndOnlyTowardSaferStates() {
        ObservabilityKillSwitches switches = new ObservabilityKillSwitches(KillSwitchState.allEnabled());

        switches.disablePayloads();
        assertFalse(switches.snapshot().payloadsEnabled());
        assertTrue(switches.snapshot().logsEnabled());
        assertTrue(switches.snapshot().eventsEnabled());
        assertTrue(switches.snapshot().metricsEnabled());
        assertEquals(
                PayloadCaptureMode.METADATA_ONLY,
                switches.snapshot().effectiveCaptureMode(PayloadCaptureMode.FULL_SANITIZED));

        switches.disableLogs();
        switches.disableEvents();
        switches.disableMetrics();
        assertFalse(switches.snapshot().logsEnabled());
        assertFalse(switches.snapshot().eventsEnabled());
        assertFalse(switches.snapshot().metricsEnabled());
    }

    @Test
    void globalSwitchDisablesCaptureWithoutMutatingBusinessState() {
        ObservabilityKillSwitches switches = new ObservabilityKillSwitches(KillSwitchState.allEnabled());
        switches.disableAll();

        assertFalse(switches.snapshot().observabilityEnabled());
        assertEquals(PayloadCaptureMode.NONE,
                switches.snapshot().effectiveCaptureMode(PayloadCaptureMode.FULL_SANITIZED));
    }
}
