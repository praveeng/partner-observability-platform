package com.samsung.sure.partner.observability.testapp.crypto;

import com.samsung.sure.partner.observability.autoconfigure.PartnerPlaintextSchema;
import com.samsung.sure.partner.observability.core.payload.PayloadFieldPolicy;
import com.samsung.sure.partner.observability.core.payload.PayloadValueType;
import com.samsung.sure.partner.observability.testapp.model.SyntheticPartnerRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class FixtureObservationConfiguration {

    @Bean
    PartnerPlaintextSchema<SyntheticPartnerRequest> alphaEncryptedResponseSchema() {
        return responseSchema("PARTNER_ALPHA_ENCRYPTED");
    }

    @Bean
    PartnerPlaintextSchema<SyntheticPartnerRequest> betaEncryptedRequestSchema() {
        return requestSchema("PARTNER_BETA_ENCRYPTED");
    }

    @Bean
    PartnerPlaintextSchema<SyntheticPartnerRequest> betaEncryptedResponseSchema() {
        return responseSchema("PARTNER_BETA_ENCRYPTED");
    }

    private PartnerPlaintextSchema<SyntheticPartnerRequest> requestSchema(String api) {
        return configure(PartnerPlaintextSchema.request(api, SyntheticPartnerRequest.class)).build();
    }

    private PartnerPlaintextSchema<SyntheticPartnerRequest> responseSchema(String api) {
        return configure(PartnerPlaintextSchema.response(api, SyntheticPartnerRequest.class)).build();
    }

    private PartnerPlaintextSchema.Builder<SyntheticPartnerRequest> configure(
            PartnerPlaintextSchema.Builder<SyntheticPartnerRequest> builder) {
        return builder
                .allowString("applicationId", SyntheticPartnerRequest::applicationId)
                .allowNumber("amount", SyntheticPartnerRequest::amount)
                .allowNumber("tenureMonths", SyntheticPartnerRequest::tenureMonths)
                .allowString("product", SyntheticPartnerRequest::product)
                .allowString("fixtureClassification", request -> attribute(request, "fixtureClassification"))
                .field("encryptionKey", PayloadFieldPolicy.REMOVE, PayloadValueType.STRING,
                        request -> attribute(request, "encryptionKey"))
                .field("initializationVector", PayloadFieldPolicy.REMOVE, PayloadValueType.STRING,
                        request -> attribute(request, "initializationVector"))
                .field("credential", PayloadFieldPolicy.REMOVE, PayloadValueType.STRING,
                        request -> attribute(request, "credential"))
                .field("document", PayloadFieldPolicy.ALLOW, PayloadValueType.STRING,
                        request -> attribute(request, "document"));
    }

    private Object attribute(SyntheticPartnerRequest request, String name) {
        if (Boolean.TRUE.equals(request.attributes().get("failObservationExtractor"))
                && "fixtureClassification".equals(name)) {
            throw new IllegalStateException("SYNTHETIC_SCHEMA_FAILURE");
        }
        return request.attributes().get(name);
    }

    @Bean
    PartnerPlaintextSchema<SyntheticPartnerRequest> alphaEncryptedRequestSchema() {
        return requestSchema("PARTNER_ALPHA_ENCRYPTED");
    }
}
