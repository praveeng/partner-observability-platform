package com.partner.observability.core.model;

/** Safe transport-boundary fact; it contains no certificate or session detail. */
public enum TransportSecurity {
    TLS,
    ALB_TLS
}
