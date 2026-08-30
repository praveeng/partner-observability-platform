package com.samsung.sure.partner.observability.testapp.model;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/** Normal synthetic business DTO. Values are generated and are not production-derived. */
public record SyntheticPartnerRequest(
        String applicationId,
        String partnerReference,
        BigDecimal amount,
        int tenureMonths,
        String product,
        Map<String, Object> attributes) {

    public static final String COLLIDING_APPLICATION_ID = "SYNTHETIC-APPLICATION-COLLISION-0001";

    public SyntheticPartnerRequest {
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(partnerReference, "partnerReference");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(product, "product");
        attributes = Map.copyOf(attributes);
    }

    public static SyntheticPartnerRequest standard(SyntheticPartner partner, String applicationId) {
        return new SyntheticPartnerRequest(
                applicationId,
                "SYNTHETIC-REF-" + partner.name(),
                new BigDecimal("1234.56"),
                12,
                "SYNTHETIC-SKU-001",
                Map.of("fixtureClassification", "SYNTHETIC_ONLY", "partnerLane", partner.name()));
    }
}
