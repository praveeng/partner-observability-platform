package com.partner.observability.core.context;

import java.util.Objects;
import java.util.Optional;

/**
 * Base class for trusted adapters. Implementations must consume authenticated server-owned state,
 * never arbitrary request headers, body fields, MDC, or telemetry fields.
 */
public abstract class PartnerContextResolver<S> {

    public final Optional<PartnerContext> resolve(S authenticatedServerSubject) {
        if (authenticatedServerSubject == null) {
            return Optional.empty();
        }
        try {
            Optional<ResolvedPartner> resolved = resolveAuthenticated(authenticatedServerSubject);
            if (resolved == null) {
                return Optional.empty();
            }
            return resolved.map(values -> PartnerContext.authenticated(
                    values.market(),
                    values.environment(),
                    values.canonicalPartnerKey(),
                    values.tenantRouteId(),
                    values.partnerSlot(),
                    values.subjectSource()));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    protected abstract Optional<ResolvedPartner> resolveAuthenticated(S authenticatedServerSubject);

    protected final ResolvedPartner authenticatedPartner(
            String market,
            DeploymentEnvironment environment,
            String canonicalPartnerKey,
            String tenantRouteId,
            String partnerSlot,
            String subjectSource) {
        return new ResolvedPartner(
                market, environment, canonicalPartnerKey, tenantRouteId, partnerSlot, subjectSource);
    }

    protected record ResolvedPartner(
            String market,
            DeploymentEnvironment environment,
            String canonicalPartnerKey,
            String tenantRouteId,
            String partnerSlot,
            String subjectSource) {
        public ResolvedPartner {
            Objects.requireNonNull(market, "market");
            Objects.requireNonNull(environment, "environment");
            Objects.requireNonNull(canonicalPartnerKey, "canonicalPartnerKey");
            Objects.requireNonNull(tenantRouteId, "tenantRouteId");
            Objects.requireNonNull(partnerSlot, "partnerSlot");
            Objects.requireNonNull(subjectSource, "subjectSource");
        }
    }
}
