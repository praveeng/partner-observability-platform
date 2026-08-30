package com.samsung.sure.partner.observability.autoconfigure.callback;

import com.samsung.sure.partner.observability.autoconfigure.CallbackObservation;
import java.util.Optional;
import org.springframework.web.server.ServerWebExchange;

public final class ReactiveCallbackObservations {
    static final String ATTRIBUTE = ReactiveCallbackObservations.class.getName() + ".observation";

    public Optional<CallbackObservation> current(ServerWebExchange exchange) {
        Object value = exchange.getAttribute(ATTRIBUTE);
        return value instanceof CallbackObservation observation ? Optional.of(observation) : Optional.empty();
    }
}
