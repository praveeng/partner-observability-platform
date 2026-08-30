package com.samsung.sure.partner.observability.autoconfigure;

/**
 * Optional low-level timing sink used by controlled performance validation. Ordinary consumers do
 * not provide this bean, so no timing is collected or retained by default.
 */
public interface ObservationPerformanceRecorder {
    String PRODUCER = "producer";
    String QUEUE_OFFER = "queue-offer";
    String CALLBACK_CAPTURE = "callback-capture";

    boolean enabled();

    void recordNanos(String operation, long elapsedNanos);

    ObservationPerformanceRecorder NONE = new ObservationPerformanceRecorder() {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public void recordNanos(String operation, long elapsedNanos) {
            // Intentionally empty.
        }
    };
}
