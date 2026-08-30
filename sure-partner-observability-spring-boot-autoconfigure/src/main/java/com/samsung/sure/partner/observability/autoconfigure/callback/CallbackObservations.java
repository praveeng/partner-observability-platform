package com.samsung.sure.partner.observability.autoconfigure.callback;

import com.samsung.sure.partner.observability.autoconfigure.CallbackObservation;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;

/** Access to the current configured callback attempt for explicit semantic lifecycle facts. */
public final class CallbackObservations {
    static final String ATTRIBUTE = CallbackObservations.class.getName() + ".observation";

    public Optional<CallbackObservation> current(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof CallbackObservation observation ? Optional.of(observation) : Optional.empty();
    }
}
