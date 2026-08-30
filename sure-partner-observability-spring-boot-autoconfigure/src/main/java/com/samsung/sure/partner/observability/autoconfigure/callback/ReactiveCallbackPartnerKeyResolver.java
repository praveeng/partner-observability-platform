package com.samsung.sure.partner.observability.autoconfigure.callback;

import java.util.Optional;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Reactive equivalent of the trusted host callback resolver; it must consume server auth state. */
@FunctionalInterface
public interface ReactiveCallbackPartnerKeyResolver {
    Mono<Optional<String>> resolveAuthenticatedPartnerKey(
            ServerWebExchange exchange, String configuredCallbackName);
}
