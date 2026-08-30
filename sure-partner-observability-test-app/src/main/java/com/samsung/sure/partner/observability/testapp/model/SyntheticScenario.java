package com.samsung.sure.partner.observability.testapp.model;

import java.util.Locale;

/** Stable scenario names shared by fixture endpoints and automated tests. */
public enum SyntheticScenario {
    NORMAL_JSON,
    SUCCESS,
    PARTNER_4XX,
    PARTNER_5XX,
    TIMEOUT,
    SLOW_RESPONSE,
    CONNECTION_FAILURE,
    RETRY,
    MALFORMED_RESPONSE,
    LARGE_NORMAL_JSON,
    PDF_BASE64_5_MB,
    JPEG_BASE64_8_MB,
    UNKNOWN_LARGE_BASE64,
    BASE64_DOCUMENT_ARRAY,
    NESTED_SENSITIVE,
    CREDENTIALS,
    OTP,
    CARD_DATA,
    RESTRICTED_PII;

    public static SyntheticScenario fromFixturePath(String value) {
        return valueOf(value.replace('-', '_').toUpperCase(Locale.ROOT));
    }
}
