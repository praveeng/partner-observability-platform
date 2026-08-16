package com.partner.observability.core.model;

public sealed interface TelemetryRecord permits PartnerApiRequest, PartnerApiResponse, PartnerEvent {
    TelemetryRecordType recordType();
}
