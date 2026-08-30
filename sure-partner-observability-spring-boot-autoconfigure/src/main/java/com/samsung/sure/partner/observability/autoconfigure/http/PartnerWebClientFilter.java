package com.samsung.sure.partner.observability.autoconfigure.http;

import com.samsung.sure.partner.observability.autoconfigure.OutboundObservation;
import com.samsung.sure.partner.observability.autoconfigure.PartnerObservationContext;
import com.samsung.sure.partner.observability.autoconfigure.PartnerObservationEngine;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

final class PartnerWebClientFilter implements ExchangeFilterFunction {
    private final PartnerObservationEngine engine;

    PartnerWebClientFilter(PartnerObservationEngine engine) { this.engine = engine; }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        return Mono.deferContextual(context -> {
            long requestLength = request.headers().getContentLength();
            OptionalLong declared = requestLength < 0 ? OptionalLong.empty() : OptionalLong.of(requestLength);
            Optional<OutboundObservation> observation = engine.startOutbound(
                    request.url(), request.method().name(), null, false,
                    request.headers().getContentType() == null ? null : request.headers().getContentType().toString(),
                    declared, 1);
            Mono<ClientResponse> exchange;
            try {
                exchange = next.exchange(request);
            } catch (RuntimeException failure) {
                observation.ifPresent(value -> value.failed(failure));
                throw failure;
            }
            Mono<ClientResponse> observed = exchange
                    .doOnNext(response -> observation.ifPresent(value -> value.complete(
                            response.rawStatusCode(), null, false,
                            response.headers().contentType().map(Object::toString).orElse(null),
                            response.headers().contentLength().isPresent()
                                    ? OptionalLong.of(response.headers().contentLength().getAsLong())
                                    : OptionalLong.empty())))
                    .doOnError(failure -> observation.ifPresent(value -> value.failed(failure)))
                    .doOnCancel(() -> observation.ifPresent(value -> value.failed(new CancellationException())));
            if (observation.isEmpty()) return observed;
            OutboundObservation value = observation.get();
            return observed.contextWrite(current -> current.put(
                    PartnerObservationContext.REACTOR_CONTEXT_KEY,
                    new PartnerObservationContext.Snapshot(value.partnerContext(), value.interactionId())));
        });
    }
}
