package com.partner.observability.testapp.telemetry;

import com.partner.observability.testapp.async.SyntheticAsyncLifecycleStore;
import com.partner.observability.testapp.async.SyntheticCallbackAuthenticator;
import com.partner.observability.testapp.fixture.LocalMockPartnerServer;
import com.partner.observability.testapp.model.SyntheticPartner;
import java.io.IOException;
import java.util.Optional;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Fixture host-security adapter that publishes only a verified server-owned result attribute. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public final class SyntheticCallbackTrustFilter extends OncePerRequestFilter {
    public static final String TRUSTED_PARTNER_ATTRIBUTE =
            SyntheticCallbackTrustFilter.class.getName() + ".trustedPartner";

    private final SyntheticCallbackAuthenticator authenticator;
    private final SyntheticAsyncLifecycleStore lifecycleStore;

    public SyntheticCallbackTrustFilter(
            SyntheticCallbackAuthenticator authenticator, SyntheticAsyncLifecycleStore lifecycleStore) {
        this.authenticator = authenticator;
        this.lifecycleStore = lifecycleStore;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/fixture/callback/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SyntheticPartner routePartner = routePartner(request.getRequestURI());
        String runId = request.getHeader(LocalMockPartnerServer.CALLBACK_RUN_HEADER);
        String signature = request.getHeader(LocalMockPartnerServer.CALLBACK_SIGNATURE_HEADER);
        if (routePartner != null && runId != null) {
            Optional<SyntheticPartner> authenticated = authenticator.authenticate(routePartner, signature);
            if (authenticated.isPresent()
                    && lifecycleStore.authorizedScenario(runId, authenticated.get()).isPresent()) {
                request.setAttribute(TRUSTED_PARTNER_ATTRIBUTE, authenticated.get().canonicalKey());
            }
        }
        chain.doFilter(request, response);
    }

    private SyntheticPartner routePartner(String path) {
        int separator = path.lastIndexOf('/');
        if (separator < 0 || separator == path.length() - 1) return null;
        try {
            return SyntheticPartner.fromFixturePath(path.substring(separator + 1));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
