package com.samsung.sure.partner.observability.core.publish;

/** Dispatcher-thread SPI. Implementations must enforce their own bounded network timeouts. */
@FunctionalInterface
public interface TelemetryPublisher {
    void publish(PublishBatch batch) throws Exception;
}
