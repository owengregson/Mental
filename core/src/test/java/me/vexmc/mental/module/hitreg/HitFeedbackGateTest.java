package me.vexmc.mental.module.hitreg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HitFeedbackGateTest {

    private final HitFeedbackGate gate = new HitFeedbackGate();
    private final UUID victim = UUID.randomUUID();

    @Test
    void admitsExactlyOncePerWindow() {
        assertTrue(gate.tryPreSend(victim, 0, 500));
        assertFalse(gate.tryPreSend(victim, 250, 500));
        assertFalse(gate.tryPreSend(victim, 499, 500));
        assertTrue(gate.tryPreSend(victim, 500, 500));
    }

    @Test
    void zeroWindowDisablesGating() {
        assertTrue(gate.tryPreSend(victim, 0, 0));
        assertTrue(gate.tryPreSend(victim, 0, 0));
    }

    @Test
    void sameMillisecondBurstAdmitsExactlyOne() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger admitted = new AtomicInteger();
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (gate.tryPreSend(victim, 1000, 500)) {
                        admitted.incrementAndGet();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, admitted.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void victimsAreIndependent() {
        UUID other = UUID.randomUUID();
        assertTrue(gate.tryPreSend(victim, 0, 500));
        assertTrue(gate.tryPreSend(other, 0, 500));
    }
}
