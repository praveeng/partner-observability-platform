package com.partner.observability.testapp.model;

/** What the mock partner observed when it attempted one callback delivery. */
public record SyntheticCallbackDelivery(
        int deliveryNumber,
        String targetPartner,
        Integer httpStatus,
        String transportOutcome) {}
