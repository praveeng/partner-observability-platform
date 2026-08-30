package com.samsung.sure.partner.observability.autoconfigure.callback;

import java.util.Optional;
import javax.servlet.http.HttpServletRequest;

/**
 * Host adapter returning a canonical key only from an authenticated principal or verified server
 * result. Route, request headers, query parameters, and body values are not trusted identity.
 */
@FunctionalInterface
public interface CallbackPartnerKeyResolver {
    Optional<String> resolveAuthenticatedPartnerKey(HttpServletRequest request, String configuredCallbackName);
}
