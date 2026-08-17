package com.partner.observability.testapp.model;

/** Fixture lifecycle facts. These mirror, but do not implement, the production SDK contract. */
public enum SyntheticLifecycleStage {
    ASYNC_REQUEST_SENT,
    ASYNC_ACK_RECEIVED,
    ASYNC_ACK_NOT_RECEIVED,
    CALLBACK_RECEIVED,
    CALLBACK_RETRY_RECEIVED,
    CALLBACK_AUTHENTICATED,
    CALLBACK_PROCESSING_STARTED,
    CALLBACK_PROCESSED,
    CALLBACK_PROCESSING_FAILED,
    CALLBACK_RESPONSE_SENT,
    CALLBACK_RESPONSE_WRITE_FAILED
}
