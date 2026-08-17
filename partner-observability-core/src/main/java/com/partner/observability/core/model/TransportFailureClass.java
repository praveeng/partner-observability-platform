package com.partner.observability.core.model;

/** Bounded type-only TLS failure classification safe for partner telemetry. */
public enum TransportFailureClass {
    TLS_HANDSHAKE,
    TLS_CERTIFICATE_VALIDATION,
    TLS_HOSTNAME_VERIFICATION,
    TLS_PROTOCOL_NEGOTIATION,
    TLS_CONFIGURATION,
    UNKNOWN_TLS
}
