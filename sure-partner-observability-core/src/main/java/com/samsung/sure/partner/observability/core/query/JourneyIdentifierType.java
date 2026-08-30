package com.samsung.sure.partner.observability.core.query;

public enum JourneyIdentifierType {
    APPLICATION_ID("application_id", true, true),
    LOAN_ID("loan_id", true, true),
    ORIGINAL_CORRELATION_ID("original_correlation_id", false, false),
    PARTNER_REFERENCE_ID("partner_reference_id", true, false),
    EXTERNAL_TRANSACTION_ID("external_transaction_id", true, false),
    CALLBACK_REFERENCE_ID("callback_reference_id", true, false),
    REQUEST_ID("request_id", false, false);

    private final String metadataKey;
    private final boolean stable;
    private final boolean singleton;

    JourneyIdentifierType(String metadataKey, boolean stable, boolean singleton) {
        this.metadataKey = metadataKey;
        this.stable = stable;
        this.singleton = singleton;
    }

    public String metadataKey() { return metadataKey; }
    public boolean stable() { return stable; }
    public boolean singleton() { return singleton; }
}
