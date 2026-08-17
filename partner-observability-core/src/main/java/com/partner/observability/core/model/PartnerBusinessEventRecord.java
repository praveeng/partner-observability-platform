package com.partner.observability.core.model;

import com.partner.observability.core.payload.SanitizationResult;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

public record PartnerBusinessEventRecord(
        String eventName,
        String journeyStage,
        Outcome outcome,
        Optional<String> errorCode,
        Optional<BigDecimal> amount,
        Optional<String> currency,
        OptionalInt tenure,
        Optional<String> tenureUnit,
        Optional<String> sku,
        Optional<String> product,
        SanitizationResult attributes) implements TelemetryRecord {

    public PartnerBusinessEventRecord {
        eventName = ModelValidation.token(eventName, 64, "eventName");
        journeyStage = ModelValidation.token(journeyStage, 64, "journeyStage");
        Objects.requireNonNull(outcome, "outcome");
        errorCode = ModelValidation.optionalToken(errorCode, 64, "errorCode");
        amount = amount == null ? Optional.empty() : amount;
        currency = ModelValidation.optionalToken(currency, 3, "currency");
        if (amount.isPresent() != currency.isPresent()) {
            throw new IllegalArgumentException("amount and currency must be present together");
        }
        tenure = tenure == null ? OptionalInt.empty() : tenure;
        if (tenure.isPresent() && tenure.getAsInt() < 0) {
            throw new IllegalArgumentException("tenure cannot be negative");
        }
        tenureUnit = ModelValidation.optionalToken(tenureUnit, 16, "tenureUnit");
        sku = ModelValidation.optionalToken(sku, 64, "sku");
        product = ModelValidation.optionalToken(product, 64, "product");
        attributes = RecordPayloads.safe(attributes, "attributes");
    }

    @Override
    public TelemetryRecordType recordType() {
        return TelemetryRecordType.PARTNER_BUSINESS_EVENT;
    }
}
