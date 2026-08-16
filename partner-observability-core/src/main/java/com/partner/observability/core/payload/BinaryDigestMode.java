package com.partner.observability.core.payload;

/** Optional fingerprinting for already-materialized, small binary candidates. Disabled by default. */
public enum BinaryDigestMode {
    DISABLED,
    SAFE_SHA_256
}
