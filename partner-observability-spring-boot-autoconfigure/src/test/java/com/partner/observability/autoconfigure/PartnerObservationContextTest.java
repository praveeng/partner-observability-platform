package com.partner.observability.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.partner.observability.core.context.PartnerContext;
import com.partner.observability.core.model.CorrelationIdentifiers;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class PartnerObservationContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void nestedScopeRestoresThreadLocalAndExistingMdcValues() {
        PartnerContext partner = partner();
        UUID outerInteraction = UUID.randomUUID();
        UUID innerInteraction = UUID.randomUUID();
        MDC.put("partner_observability_slot", "preexisting-slot");

        try (PartnerObservationContext.Scope outer = PartnerObservationContext.open(partner, outerInteraction)) {
            assertThat(PartnerObservationContext.current()).get()
                    .extracting(PartnerObservationContext.Snapshot::interactionId)
                    .isEqualTo(outerInteraction);
            try (PartnerObservationContext.Scope inner = PartnerObservationContext.open(partner, innerInteraction)) {
                assertThat(PartnerObservationContext.current()).get()
                        .extracting(PartnerObservationContext.Snapshot::interactionId)
                        .isEqualTo(innerInteraction);
            }
            assertThat(PartnerObservationContext.current()).get()
                    .extracting(PartnerObservationContext.Snapshot::interactionId)
                    .isEqualTo(outerInteraction);
        }

        assertThat(PartnerObservationContext.current()).isEmpty();
        assertThat(MDC.get("partner_observability_slot")).isEqualTo("preexisting-slot");
        assertThat(MDC.get("partner_observability_interaction_id")).isNull();
    }

    @Test
    void taskDecoratorCarriesImmutableSnapshotAndClearsWorkerState() {
        PartnerContext partner = partner();
        UUID interaction = UUID.randomUUID();
        AtomicReference<PartnerObservationContext.Snapshot> observed = new AtomicReference<>();
        Runnable decorated;

        try (PartnerObservationContext.Scope ignored = PartnerObservationContext.open(partner, interaction)) {
            decorated = new PartnerObservationTaskDecorator().decorate(
                    () -> observed.set(PartnerObservationContext.current().orElseThrow()));
        }

        assertThat(PartnerObservationContext.current()).isEmpty();
        decorated.run();
        assertThat(observed.get().partnerContext()).isEqualTo(partner);
        assertThat(observed.get().interactionId()).isEqualTo(interaction);
        assertThat(PartnerObservationContext.current()).isEmpty();
        assertThat(MDC.get("partner_observability_slot")).isNull();
    }

    @Test
    void callbackIdentifiersUpdateOnlyTheMatchingTrustedScope() {
        PartnerContext partner = partner();
        UUID interaction = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        CorrelationIdentifiers identifiers = new CorrelationIdentifiers(
                Optional.of("APP-CALLBACK-001"),
                Optional.of("LOAN-CALLBACK-001"),
                Optional.of("CORR-CALLBACK-001"),
                Optional.of("PREF-CALLBACK-001"),
                Optional.empty(),
                Optional.of("CBREF-CALLBACK-001"),
                Optional.empty());

        try (PartnerObservationContext.Scope ignored = PartnerObservationContext.openCallback(
                partner, interaction, attempt, "callback-profile")) {
            PartnerObservationContext.updateCallbackIdentifiers(interaction, UUID.randomUUID(), identifiers);
            assertThat(PartnerObservationContext.current().orElseThrow().identifiers())
                    .isEqualTo(CorrelationIdentifiers.empty());

            PartnerObservationContext.updateCallbackIdentifiers(interaction, attempt, identifiers);
            assertThat(PartnerObservationContext.current().orElseThrow().identifiers()).isEqualTo(identifiers);
        }
        assertThat(PartnerObservationContext.current()).isEmpty();
    }

    private PartnerContext partner() {
        PartnerObservabilityProperties properties = new PartnerObservabilityProperties();
        PartnerObservabilityProperties.Partner configured = new PartnerObservabilityProperties.Partner();
        configured.setKey("partner-a");
        configured.setTenantRouteId("tenant-a");
        configured.setSlot("p001");
        properties.getPartners().add(configured);
        return new ConfiguredObservationRegistry(properties).partner("partner-a").orElseThrow();
    }
}
