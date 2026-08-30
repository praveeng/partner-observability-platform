package com.samsung.sure.partner.observability.core.policy;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/** Runtime controls may only reduce the startup-authorized state. */
public final class ObservabilityKillSwitches {

    private final AtomicReference<KillSwitchState> state;

    public ObservabilityKillSwitches(KillSwitchState startupState) {
        state = new AtomicReference<>(Objects.requireNonNull(startupState, "startupState"));
    }

    public KillSwitchState snapshot() {
        return state.get();
    }

    public void disableAll() {
        reduce(current -> new KillSwitchState(
                false,
                current.payloadsEnabled(),
                current.logsEnabled(),
                current.eventsEnabled(),
                current.metricsEnabled(),
                current.exportEnabled()));
    }

    public void disablePayloads() {
        reduce(current -> new KillSwitchState(
                current.observabilityEnabled(), false, current.logsEnabled(), current.eventsEnabled(), current.metricsEnabled(), current.exportEnabled()));
    }

    public void disableLogs() {
        reduce(current -> new KillSwitchState(
                current.observabilityEnabled(), current.payloadsEnabled(), false, current.eventsEnabled(), current.metricsEnabled(), current.exportEnabled()));
    }

    public void disableEvents() {
        reduce(current -> new KillSwitchState(
                current.observabilityEnabled(), current.payloadsEnabled(), current.logsEnabled(), false, current.metricsEnabled(), current.exportEnabled()));
    }

    public void disableMetrics() {
        reduce(current -> new KillSwitchState(
                current.observabilityEnabled(), current.payloadsEnabled(), current.logsEnabled(), current.eventsEnabled(), false, current.exportEnabled()));
    }

    public void disableExport() {
        reduce(current -> new KillSwitchState(
                current.observabilityEnabled(), current.payloadsEnabled(), current.logsEnabled(), current.eventsEnabled(), current.metricsEnabled(), false));
    }

    private void reduce(UnaryOperator<KillSwitchState> operation) {
        state.updateAndGet(operation);
    }
}
