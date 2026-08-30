package com.samsung.sure.partner.observability.autoconfigure.callback;

import com.samsung.sure.partner.observability.autoconfigure.CallbackObservation;
import com.samsung.sure.partner.observability.autoconfigure.ConfiguredObservationRegistry;
import com.samsung.sure.partner.observability.autoconfigure.ObservationDefinition;
import com.samsung.sure.partner.observability.autoconfigure.ObservationMetrics;
import com.samsung.sure.partner.observability.autoconfigure.PartnerObservationContext;
import com.samsung.sure.partner.observability.autoconfigure.PartnerObservationEngine;
import com.samsung.sure.partner.observability.core.model.TransportOutcome;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

final class PartnerCallbackMvcFilter extends OncePerRequestFilter {
    private final ConfiguredObservationRegistry registry;
    private final CallbackPartnerKeyResolver partnerResolver;
    private final PartnerObservationEngine engine;
    private final ObservationMetrics metrics;

    PartnerCallbackMvcFilter(
            ConfiguredObservationRegistry registry,
            CallbackPartnerKeyResolver partnerResolver,
            PartnerObservationEngine engine,
            ObservationMetrics metrics) {
        this.registry = registry;
        this.partnerResolver = partnerResolver;
        this.engine = engine;
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        Optional<ObservationDefinition> matched = registry.callback(request.getMethod(), path);
        if (matched.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        ObservationDefinition definition = matched.get();
        Optional<String> resolved;
        try {
            resolved = partnerResolver.resolveAuthenticatedPartnerKey(request, definition.name());
        } catch (RuntimeException exception) {
            resolved = Optional.empty();
        }
        if (resolved.isEmpty()) {
            metrics.callbackDenied("untrusted");
            chain.doFilter(request, response);
            return;
        }
        if (!definition.partnerContext().canonicalPartnerKey().equals(resolved.get())) {
            metrics.callbackDenied("conflict");
            chain.doFilter(request, response);
            return;
        }
        long length = request.getContentLengthLong();
        CallbackObservation observation = engine.startCallback(
                definition, Instant.now(), request.getMethod(), request.getContentType(),
                length < 0 ? OptionalLong.empty() : OptionalLong.of(length));
        request.setAttribute(CallbackObservations.ATTRIBUTE, observation);
        Throwable failure = null;
        try (PartnerObservationContext.Scope ignored = PartnerObservationContext.openCallback(
                definition.partnerContext(), observation.interactionId(),
                observation.callbackAttemptId(), definition.correlationProfile())) {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            observation.receivedMetadataOnly();
            observation.response(
                    response.getStatus(), failure == null
                            ? TransportOutcome.WRITE_COMPLETED : TransportOutcome.WRITE_FAILED);
            request.removeAttribute(CallbackObservations.ATTRIBUTE);
        }
    }
}
