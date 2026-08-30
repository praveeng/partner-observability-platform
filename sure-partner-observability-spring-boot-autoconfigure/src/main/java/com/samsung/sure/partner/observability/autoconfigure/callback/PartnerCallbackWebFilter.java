package com.samsung.sure.partner.observability.autoconfigure.callback;

import com.samsung.sure.partner.observability.autoconfigure.CallbackObservation;
import com.samsung.sure.partner.observability.autoconfigure.ConfiguredObservationRegistry;
import com.samsung.sure.partner.observability.autoconfigure.ObservationDefinition;
import com.samsung.sure.partner.observability.autoconfigure.ObservationMetrics;
import com.samsung.sure.partner.observability.autoconfigure.PartnerObservationContext;
import com.samsung.sure.partner.observability.autoconfigure.PartnerObservationEngine;
import com.samsung.sure.partner.observability.core.model.TransportOutcome;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

final class PartnerCallbackWebFilter implements WebFilter {
    private final ConfiguredObservationRegistry registry;
    private final ReactiveCallbackPartnerKeyResolver partnerResolver;
    private final PartnerObservationEngine engine;
    private final ObservationMetrics metrics;

    PartnerCallbackWebFilter(
            ConfiguredObservationRegistry registry,
            ReactiveCallbackPartnerKeyResolver partnerResolver,
            PartnerObservationEngine engine,
            ObservationMetrics metrics) {
        this.registry = registry;
        this.partnerResolver = partnerResolver;
        this.engine = engine;
        this.metrics = metrics;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String method = exchange.getRequest().getMethodValue();
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        Optional<ObservationDefinition> matched = registry.callback(method, path);
        if (matched.isEmpty()) return chain.filter(exchange);
        ObservationDefinition definition = matched.get();
        return partnerResolver.resolveAuthenticatedPartnerKey(exchange, definition.name())
                .onErrorReturn(Optional.empty())
                .flatMap(resolved -> {
                    if (resolved.isEmpty()) {
                        metrics.callbackDenied("untrusted");
                        return chain.filter(exchange);
                    }
                    if (!definition.partnerContext().canonicalPartnerKey().equals(resolved.get())) {
                        metrics.callbackDenied("conflict");
                        return chain.filter(exchange);
                    }
                    long length = exchange.getRequest().getHeaders().getContentLength();
                    CallbackObservation observation = engine.startCallback(
                            definition, Instant.now(), method,
                            exchange.getRequest().getHeaders().getContentType() == null
                                    ? null : exchange.getRequest().getHeaders().getContentType().toString(),
                            length < 0 ? OptionalLong.empty() : OptionalLong.of(length));
                    observation.receivedMetadataOnly();
                    exchange.getAttributes().put(ReactiveCallbackObservations.ATTRIBUTE, observation);
                    Mono<Void> result = chain.filter(exchange)
                            .doOnSuccess(ignored -> observation.response(
                                    status(exchange), TransportOutcome.WRITE_COMPLETED))
                            .doOnError(error -> observation.response(
                                    status(exchange), TransportOutcome.WRITE_FAILED))
                            .doOnCancel(() -> observation.response(
                                    status(exchange), TransportOutcome.CANCELLED))
                            .doFinally(signal -> exchange.getAttributes().remove(ReactiveCallbackObservations.ATTRIBUTE));
                    return result.contextWrite(context -> context.put(
                            PartnerObservationContext.REACTOR_CONTEXT_KEY,
                            new PartnerObservationContext.Snapshot(
                                    definition.partnerContext(), observation.interactionId())));
                });
    }

    private int status(ServerWebExchange exchange) {
        HttpStatus status = exchange.getResponse().getStatusCode();
        return status == null ? 200 : status.value();
    }
}
