package com.partner.observability.autoconfigure.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;

/**
 * An exception-contained local appender. It deliberately never formats a message or reads a
 * throwable proxy.
 */
final class PartnerSafeLogAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private final PartnerSafeLogCapture capture;

    PartnerSafeLogAppender(PartnerSafeLogCapture capture) {
        this.capture = capture;
    }

    @Override
    protected void append(ILoggingEvent event) {
        try {
            capture.capture(event);
        } catch (StackOverflowError | LinkageError | RuntimeException ignored) {
            // A compatibility-copy failure must never affect another appender or the log caller.
        }
    }
}
