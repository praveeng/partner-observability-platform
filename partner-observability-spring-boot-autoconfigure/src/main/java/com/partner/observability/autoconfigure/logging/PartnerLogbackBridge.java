package com.partner.observability.autoconfigure.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

/**
 * Adds one observer appender without replacing, filtering, or reconfiguring existing appenders.
 */
final class PartnerLogbackBridge implements AutoCloseable {

    static final String APPENDER_NAME = "PARTNER_OBSERVABILITY_SAFE_COPY";

    private final PartnerSafeLogAppender appender;
    private Logger root;
    private boolean started;

    PartnerLogbackBridge(PartnerSafeLogCapture capture) {
        appender = new PartnerSafeLogAppender(capture);
        appender.setName(APPENDER_NAME);
    }

    synchronized void start() {
        if (started) {
            return;
        }
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            return;
        }
        root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        if (root.getAppender(APPENDER_NAME) != null) {
            return;
        }
        appender.setContext(context);
        appender.start();
        root.addAppender(appender);
        started = true;
    }

    @Override
    public synchronized void close() {
        if (!started) {
            return;
        }
        if (root != null) {
            root.detachAppender(appender);
        }
        appender.stop();
        root = null;
        started = false;
    }

    PartnerSafeLogAppender appender() {
        return appender;
    }
}
