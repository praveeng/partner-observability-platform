package com.partner.observability.core.model;

public enum TelemetryRecordType {
    OUTBOUND_API_REQUEST("outbound_api_request", EventDomain.API),
    OUTBOUND_API_RESPONSE("outbound_api_response", EventDomain.API),
    ASYNC_ACKNOWLEDGEMENT("async_acknowledgement", EventDomain.ASYNC),
    CALLBACK_REQUEST("callback_request", EventDomain.CALLBACK),
    CALLBACK_RESPONSE("callback_response", EventDomain.CALLBACK),
    CALLBACK_PROCESSING_EVENT("callback_processing_event", EventDomain.CALLBACK),
    PARTNER_BUSINESS_EVENT("partner_business_event", EventDomain.BUSINESS),
    /** Schema-1 values retained only for bounded N-1 migration reads. */
    API_REQUEST("api_request", EventDomain.API),
    API_RESPONSE("api_response", EventDomain.API),
    PARTNER_EVENT("partner_event", EventDomain.BUSINESS);

    private final String wireValue;
    private final EventDomain eventDomain;

    TelemetryRecordType(String wireValue, EventDomain eventDomain) {
        this.wireValue = wireValue;
        this.eventDomain = eventDomain;
    }

    public String wireValue() {
        return wireValue;
    }

    public EventDomain eventDomain() {
        return eventDomain;
    }
}
