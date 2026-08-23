package com.partner.observability.core.model;

/** Bounded transport result used only for aggregate HTTP health metrics. */
public enum HttpResult {
    HTTP_1XX,
    HTTP_2XX,
    HTTP_3XX,
    HTTP_4XX,
    HTTP_5XX,
    TIMEOUT,
    CONNECTION_FAILURE,
    CANCELLED,
    UNKNOWN
}
