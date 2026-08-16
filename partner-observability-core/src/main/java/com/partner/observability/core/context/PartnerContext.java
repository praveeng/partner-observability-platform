package com.partner.observability.core.context;

import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable routing identity created only through a configured server-side resolver. */
public final class PartnerContext {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,62}");
    private static final Pattern TENANT = Pattern.compile("[a-z0-9-]{1,40}");
    private static final Pattern SLOT = Pattern.compile("p0(?:0[1-9]|[1-5][0-9]|6[0-4])");

    private final String market;
    private final DeploymentEnvironment environment;
    private final String canonicalPartnerKey;
    private final String tenantRouteId;
    private final String partnerSlot;
    private final String subjectSource;
    private final TrustLevel trustLevel;

    private PartnerContext(
            String market,
            DeploymentEnvironment environment,
            String canonicalPartnerKey,
            String tenantRouteId,
            String partnerSlot,
            String subjectSource,
            TrustLevel trustLevel) {
        this.market = validToken(market, "market");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.canonicalPartnerKey = validToken(canonicalPartnerKey, "canonicalPartnerKey");
        this.tenantRouteId = valid(tenantRouteId, TENANT, "tenantRouteId");
        this.partnerSlot = valid(partnerSlot, SLOT, "partnerSlot");
        this.subjectSource = validToken(subjectSource, "subjectSource");
        if (trustLevel != TrustLevel.AUTHENTICATED_SERVER) {
            throw new IllegalArgumentException("PartnerContext requires authenticated server trust");
        }
        this.trustLevel = trustLevel;
    }

    static PartnerContext authenticated(
            String market,
            DeploymentEnvironment environment,
            String canonicalPartnerKey,
            String tenantRouteId,
            String partnerSlot,
            String subjectSource) {
        return new PartnerContext(
                market,
                environment,
                canonicalPartnerKey,
                tenantRouteId,
                partnerSlot,
                subjectSource,
                TrustLevel.AUTHENTICATED_SERVER);
    }

    public String market() {
        return market;
    }

    public DeploymentEnvironment environment() {
        return environment;
    }

    public String canonicalPartnerKey() {
        return canonicalPartnerKey;
    }

    /** Internal routing value; never serialize it into a partner-visible line. */
    public String tenantRouteId() {
        return tenantRouteId;
    }

    public String partnerSlot() {
        return partnerSlot;
    }

    public String subjectSource() {
        return subjectSource;
    }

    public TrustLevel trustLevel() {
        return trustLevel;
    }

    public RoutingKey routingKey() {
        return new RoutingKey(market, environment, canonicalPartnerKey);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PartnerContext that)) {
            return false;
        }
        return market.equals(that.market)
                && environment == that.environment
                && canonicalPartnerKey.equals(that.canonicalPartnerKey)
                && tenantRouteId.equals(that.tenantRouteId)
                && partnerSlot.equals(that.partnerSlot)
                && subjectSource.equals(that.subjectSource)
                && trustLevel == that.trustLevel;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                market, environment, canonicalPartnerKey, tenantRouteId, partnerSlot, subjectSource, trustLevel);
    }

    @Override
    public String toString() {
        return "PartnerContext[market=" + market
                + ", environment=" + environment
                + ", canonicalPartnerKey=" + canonicalPartnerKey
                + ", partnerSlot=" + partnerSlot
                + ", subjectSource=" + subjectSource
                + ", trustLevel=" + trustLevel + "]";
    }

    private static String validToken(String value, String name) {
        return valid(value, TOKEN, name);
    }

    private static String valid(String value, Pattern pattern, String name) {
        Objects.requireNonNull(value, name);
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " is not a configured safe token");
        }
        return value;
    }

    public record RoutingKey(String market, DeploymentEnvironment environment, String canonicalPartnerKey) {}
}
