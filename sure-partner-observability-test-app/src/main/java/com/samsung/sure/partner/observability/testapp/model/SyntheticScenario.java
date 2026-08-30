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
    MIXED_LARGE_JSON_96_KIB,
    PDF_REQUEST_BASE64_5_MB,
    JPEG_REQUEST_BASE64_8_MB,
    UNKNOWN_REQUEST_LARGE_BASE64,
    MALFORMED_RESPONSE_BINARY_REQUEST,
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
