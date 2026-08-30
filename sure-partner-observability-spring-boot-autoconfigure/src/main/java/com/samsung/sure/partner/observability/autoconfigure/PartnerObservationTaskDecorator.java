package com.samsung.sure.partner.observability.autoconfigure;

import java.util.Optional;
import org.springframework.core.task.TaskDecorator;

/** Opt-in-safe decorator used automatically by Boot's configurable application task executor. */
public final class PartnerObservationTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable task) {
        Optional<PartnerObservationContext.Snapshot> snapshot = PartnerObservationContext.current();
        return snapshot.map(value -> value.wrap(task)).orElse(task);
    }
}
