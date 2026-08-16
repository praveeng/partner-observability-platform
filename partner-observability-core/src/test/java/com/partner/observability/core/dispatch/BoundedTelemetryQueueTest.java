package com.partner.observability.core.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.partner.observability.core.TestFixtures;
import com.partner.observability.core.context.PartnerContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BoundedTelemetryQueueTest {

    @Test
    void multiProducerAccountingReturnsToZeroAfterDrain() throws Exception {
        PartnerContext context = TestFixtures.context("partner-a", "uk-dev-partner-a", "p001");
        BoundedTelemetryQueue queue = new BoundedTelemetryQueue(1024, 1024L * 256);
        int producers = 4;
        int attempts = 500;
        AtomicInteger accepted = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(producers);
        CountDownLatch done = new CountDownLatch(producers);
        for (int producer = 0; producer < producers; producer++) {
            executor.execute(() -> {
                for (int index = 0; index < attempts; index++) {
                    if (queue.offer(TestFixtures.submission(context, 256)) == BoundedTelemetryQueue.OfferResult.ACCEPTED) {
                        accepted.incrementAndGet();
                    }
                }
                done.countDown();
            });
        }
        assertEquals(true, done.await(5, TimeUnit.SECONDS));
        executor.shutdownNow();

        List<TelemetrySubmission> drained = new ArrayList<>();
        TelemetrySubmission item;
        while ((item = queue.poll()) != null) {
            drained.add(item);
        }

        assertEquals(accepted.get(), drained.size());
        assertEquals(0, queue.size());
        assertEquals(0, queue.bytes());
    }
}
