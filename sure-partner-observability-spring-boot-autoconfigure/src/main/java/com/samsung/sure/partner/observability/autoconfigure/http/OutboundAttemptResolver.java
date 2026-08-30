package com.samsung.sure.partner.observability.autoconfigure.http;

import java.net.URI;

/** Trusted application retry extension; values are bounded to 1..10 by the observation engine. */
@FunctionalInterface
public interface OutboundAttemptResolver {
    int attempt(String configuredApiName, URI endpoint);
}
