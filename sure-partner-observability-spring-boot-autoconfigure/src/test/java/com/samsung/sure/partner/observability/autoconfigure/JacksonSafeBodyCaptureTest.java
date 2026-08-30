package com.samsung.sure.partner.observability.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.samsung.sure.partner.observability.core.payload.FailClosedPayloadSanitizer;
import com.samsung.sure.partner.observability.core.payload.PayloadStatus;
import com.samsung.sure.partner.observability.core.policy.PayloadCaptureMode;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class JacksonSafeBodyCaptureTest {
    @Test
    void rejectsLargeDecodedTreeBeforeCopyingItIntoASafeProjection() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode source = mapper.createObjectNode();
        source.put("opaque", "A".repeat(10 * 1024 * 1024));
        ObservationDefinition definition = definition();

        CapturedBody captured = new JacksonSafeBodyCapture(
                        mapper, new FailClosedPayloadSanitizer(), List.of())
                .capture(
                        definition, ObservationLeg.CALLBACK_REQUEST, source, "application/json",
                        OptionalLong.empty(), PayloadCaptureMode.FULL_SANITIZED);

        assertThat(captured.payload().status()).isEqualTo(PayloadStatus.OVERSIZE);
        assertThat(captured.payload().payload()).isEmpty();
        assertThat(captured.identifiers()).isEqualTo(com.samsung.sure.partner.observability.core.model.CorrelationIdentifiers.empty());
    }

    private ObservationDefinition definition() {
        PartnerObservabilityProperties properties = new PartnerObservabilityProperties();
        PartnerObservabilityProperties.Partner partner = new PartnerObservabilityProperties.Partner();
        partner.setKey("partner-a");
        partner.setTenantRouteId("tenant-a");
        partner.setSlot("p001");
        properties.getPartners().add(partner);
        PartnerObservabilityProperties.Callback callback = new PartnerObservabilityProperties.Callback();
        callback.setName("callback-a");
        callback.setPath("/callbacks/a");
        callback.setPartner("partner-a");
        callback.setCaptureMode(PayloadCaptureMode.FULL_SANITIZED);
        callback.getSafeFields().add("opaque");
        properties.getCallbacks().add(callback);
        new PartnerObservabilityConfigurationValidator().validate(properties);
        return new ConfiguredObservationRegistry(properties).callbackDefinitions().get(0);
    }
}
