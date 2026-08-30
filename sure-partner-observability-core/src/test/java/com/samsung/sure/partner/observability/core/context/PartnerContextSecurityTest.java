package com.samsung.sure.partner.observability.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.samsung.sure.partner.observability.core.TestFixtures;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PartnerContextSecurityTest {

    @Test
    void exposesExactlyTheFourCanonicalEnvironmentValues() {
        assertEquals(
                java.util.List.of("local", "dev", "stage", "prod"),
                Arrays.stream(DeploymentEnvironment.values())
                        .map(DeploymentEnvironment::canonicalValue)
                        .toList());
    }

    @Test
    void onlyResolvesConfiguredServerOwnedSubjects() {
        PartnerContextResolver<String> resolver = new PartnerContextResolver<>() {
            @Override
            protected Optional<ResolvedPartner> resolveAuthenticated(String subject) {
                return "server-session-a".equals(subject)
                        ? Optional.of(authenticatedPartner(
                                "uk", DeploymentEnvironment.PROD, "partner-a", "uk-prod-partner-a", "p001", "iam"))
                        : Optional.empty();
            }
        };

        assertTrue(resolver.resolve("server-session-a").isPresent());
        assertTrue(resolver.resolve("spoofed-partner-id-b").isEmpty());
        assertTrue(resolver.resolve(null).isEmpty());
    }

    @Test
    void resolverFailureAndInvalidConfigurationFailClosed() {
        PartnerContextResolver<String> failing = new PartnerContextResolver<>() {
            @Override
            protected Optional<ResolvedPartner> resolveAuthenticated(String subject) {
                throw new IllegalStateException("synthetic resolver failure");
            }
        };
        PartnerContextResolver<String> invalid = new PartnerContextResolver<>() {
            @Override
            protected Optional<ResolvedPartner> resolveAuthenticated(String subject) {
                return Optional.of(authenticatedPartner(
                        "uk", DeploymentEnvironment.PROD, "partner-a", "client-spoofed/tenant", "p001", "iam"));
            }
        };

        assertTrue(failing.resolve("server-session-a").isEmpty());
        assertTrue(invalid.resolve("server-session-a").isEmpty());
    }

    @Test
    void validatesBoundedConfiguredIdentityAndDoesNotDiscloseTenantInDiagnostics() {
        PartnerContext context = TestFixtures.context("partner-a", "uk-dev-partner-a", "p001");

        assertEquals(TrustLevel.AUTHENTICATED_SERVER, context.trustLevel());
        assertFalse(context.toString().contains("uk-dev-partner-a"));
        assertTrue(new Resolver("../tenant", "p001").resolve("subject").isEmpty());
        assertTrue(new Resolver("safe-tenant", "p999").resolve("subject").isEmpty());
    }

    @Test
    void sameApplicationIdUnderTwoPartnersStillHasDistinctRoutingKeys() {
        PartnerContext partnerA = TestFixtures.context("partner-a", "uk-dev-partner-a", "p001");
        PartnerContext partnerB = TestFixtures.context("partner-b", "uk-dev-partner-b", "p002");

        assertNotEquals(partnerA.routingKey(), partnerB.routingKey());
        assertNotEquals(partnerA.tenantRouteId(), partnerB.tenantRouteId());
    }

    private static final class Resolver extends PartnerContextResolver<String> {
        private final String tenant;
        private final String slot;

        private Resolver(String tenant, String slot) {
            this.tenant = tenant;
            this.slot = slot;
        }

        @Override
        protected Optional<ResolvedPartner> resolveAuthenticated(String subject) {
            return Optional.of(authenticatedPartner(
                    "uk", DeploymentEnvironment.DEV, "partner-a", tenant, slot, "test"));
        }
    }
}
