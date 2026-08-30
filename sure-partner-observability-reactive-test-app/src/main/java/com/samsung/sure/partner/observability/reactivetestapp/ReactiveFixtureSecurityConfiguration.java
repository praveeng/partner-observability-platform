package com.samsung.sure.partner.observability.reactivetestapp;

import com.samsung.sure.partner.observability.autoconfigure.callback.ReactiveCallbackPartnerKeyResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

/** Validates a synthetic fixture credential before publishing immutable partner context. */
@Configuration(proxyBeanMethods = false)
public class ReactiveFixtureSecurityConfiguration {
    static final String TRUSTED_PARTNER = ReactiveFixtureSecurityConfiguration.class.getName() + ".partner";
    static final String FIXTURE_KEY_HEADER = "X-Synthetic-Callback-Key";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 50)
    WebFilter syntheticReactiveCallbackAuthenticator(
            @Value("${local-synthetic.callback-key}") String expectedKey) {
        byte[] expected = expectedKey.getBytes(StandardCharsets.UTF_8);
        return (exchange, chain) -> {
            String path = exchange.getRequest().getPath().pathWithinApplication().value();
            if (!path.startsWith("/fixture/reactive/")) {
                return chain.filter(exchange);
            }
            String supplied = exchange.getRequest().getHeaders().getFirst(FIXTURE_KEY_HEADER);
            String lane = path.endsWith("/alpha") ? "partner-alpha-fixture"
                    : path.endsWith("/beta") ? "partner-beta-fixture" : null;
            if (lane != null && supplied != null
                    && MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8))) {
                exchange.getAttributes().put(TRUSTED_PARTNER, lane);
            }
            return chain.filter(exchange);
        };
    }

    @Bean
    ReactiveCallbackPartnerKeyResolver syntheticReactiveCallbackPartnerResolver() {
        return (exchange, callbackName) -> Mono.just(Optional.ofNullable(
                (String) exchange.getAttribute(TRUSTED_PARTNER)));
    }

    @Bean
    ReactiveFixtureMetrics reactiveFixtureMetrics() {
        return new ReactiveFixtureMetrics();
    }

    static final class ReactiveFixtureMetrics {
        private static final long DEFERRED_CAPACITY = 4096;
        private final java.util.concurrent.atomic.LongAdder subscriptions = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.LongAdder completed = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.LongAdder cancelled = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.LongAdder errors = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.LongAdder contextConflicts = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.LongAdder doubleSubscriptions = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.LongAdder doubleTerminalEvents = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.LongAdder elementsEmitted = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.AtomicLong active = new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicLong maximumActive = new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicLong deferredActive = new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicLong maximumDeferredActive = new java.util.concurrent.atomic.AtomicLong();

        void subscribed() {
            subscriptions.increment();
            long current = active.incrementAndGet();
            maximumActive.accumulateAndGet(current, Math::max);
        }
        void completed() { completed.increment(); active.decrementAndGet(); }
        void cancelled() { cancelled.increment(); active.decrementAndGet(); }
        void errored() { errors.increment(); active.decrementAndGet(); }
        void contextConflict() { contextConflicts.increment(); }
        void doubleSubscription() { doubleSubscriptions.increment(); }
        void doubleTerminal() { doubleTerminalEvents.increment(); }
        void element() { elementsEmitted.increment(); }

        boolean beginDeferred() {
            while (true) {
                long current = deferredActive.get();
                if (current >= DEFERRED_CAPACITY) return false;
                if (deferredActive.compareAndSet(current, current + 1)) {
                    maximumDeferredActive.accumulateAndGet(current + 1, Math::max);
                    return true;
                }
            }
        }

        void endDeferred() {
            deferredActive.decrementAndGet();
        }

        Map<String, Long> snapshot() {
            Map<String, Long> result = new java.util.LinkedHashMap<>();
            result.put("subscriptions", subscriptions.sum());
            result.put("completed", completed.sum());
            result.put("cancelled", cancelled.sum());
            result.put("errors", errors.sum());
            result.put("contextConflicts", contextConflicts.sum());
            result.put("doubleSubscriptions", doubleSubscriptions.sum());
            result.put("doubleTerminalEvents", doubleTerminalEvents.sum());
            result.put("elementsEmitted", elementsEmitted.sum());
            result.put("active", active.get());
            result.put("maximumActive", maximumActive.get());
            result.put("deferredActive", deferredActive.get());
            result.put("maximumDeferredActive", maximumDeferredActive.get());
            result.put("deferredCapacity", DEFERRED_CAPACITY);
            return Map.copyOf(result);
        }

        void reset() {
            subscriptions.reset();
            completed.reset();
            cancelled.reset();
            errors.reset();
            contextConflicts.reset();
            doubleSubscriptions.reset();
            doubleTerminalEvents.reset();
            elementsEmitted.reset();
            active.set(0);
            maximumActive.set(0);
            deferredActive.set(0);
            maximumDeferredActive.set(0);
        }
    }
}
