package com.samsung.sure.partner.observability.core.model;

public sealed interface TelemetryRecord permits
        OutboundApiRequestRecord,
        OutboundApiResponseRecord,
        AsyncAcknowledgementRecord,
        CallbackRequestRecord,
        CallbackResponseRecord,
        CallbackProcessingEventRecord,
        PartnerBusinessEventRecord,
        PartnerApiRequest,
        PartnerApiResponse,
        PartnerEvent {
    TelemetryRecordType recordType();
}
