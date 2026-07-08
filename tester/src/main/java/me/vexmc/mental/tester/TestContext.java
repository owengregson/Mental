package me.vexmc.mental.tester;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.platform.TaskHandle;
import org.jetbrains.annotations.NotNull;

/**
 * Bridges the off-thread test driver into the live server: synchronous
 * hops onto the global tick, tick-count waits, and assertions.
 */
public final class TestContext {

    // Generous: a concurrent matrix can momentarily starve a healthy server
    // (the host, not the suite, is the bottleneck); genuinely dead servers
    // are caught by the launcher's hard per-server watchdog.
    private static final long SYNC_TIMEOUT_SECONDS = 90;
    private static final long TICK_WAIT_TIMEOUT_SECONDS = 120;

    private final Scheduling scheduling;
    private final Logger logger;

    TestContext(@NotNull Scheduling scheduling, @NotNull Logger logger) {
        this.scheduling = scheduling;
        this.logger = logger;
    }

    public <T> T sync(@NotNull Callable<T> work) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        scheduling.runGlobal(() -> {
            try {
                future.complete(work.call());
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        try {
            return future.get(SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            throw new AssertionError("Synchronous work did not complete within "
                    + SYNC_TIMEOUT_SECONDS + "s — is the server tick stalled?");
        }
    }

    public void syncRun(@NotNull ThrowingRunnable work) throws Exception {
        sync(() -> {
            work.run();
            return null;
        });
    }

    public void awaitTicks(int ticks) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(ticks);
        TaskHandle[] handle = new TaskHandle[1];
        handle[0] = scheduling.repeatGlobal(1L, 1L, () -> {
            latch.countDown();
            if (latch.getCount() == 0 && handle[0] != null) {
                handle[0].cancel();
            }
        });
        if (!latch.await(TICK_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            handle[0].cancel();
            throw new AssertionError("Waited " + TICK_WAIT_TIMEOUT_SECONDS + "s for "
                    + ticks + " ticks — server tick stalled?");
        }
    }

    /**
     * Ticks until the condition holds — condition-based waiting where a
     * fixed sleep would race the matrix: under nine concurrent servers,
     * event dispatch can lag a fixed 3-tick window and a last-write-wins
     * captor then reads stale spawn-time state instead of the knock.
     */
    public void awaitUntil(@NotNull BooleanSupplier condition, int maxTicks, @NotNull String what)
            throws InterruptedException {
        awaitUntil(condition, maxTicks, () -> what);
    }

    /**
     * As {@link #awaitUntil(BooleanSupplier, int, String)}, but the description
     * is built lazily AT TIMEOUT TIME — for waits whose failure message should
     * report the last observed state (a timeout that says only "timed out"
     * costs a full diagnostic round on a version-specific matrix entry).
     */
    public void awaitUntil(
            @NotNull BooleanSupplier condition, int maxTicks, @NotNull Supplier<String> what)
            throws InterruptedException {
        for (int tick = 0; tick < maxTicks && !condition.getAsBoolean(); tick++) {
            awaitTicks(1);
        }
        expect(condition.getAsBoolean(),
                "timed out after " + maxTicks + " ticks waiting for " + what.get());
    }

    public void expect(boolean condition, @NotNull String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public void expectNear(double expected, double actual, double epsilon, @NotNull String what) {
        if (Double.isNaN(actual) || Math.abs(expected - actual) > epsilon) {
            throw new AssertionError(what + ": expected " + expected + " ± " + epsilon
                    + " but was " + actual);
        }
    }

    public void note(@NotNull String message) {
        logger.info("[test] " + message);
    }

    /**
     * Declares the current scenario un-exercisable on this server and aborts it as an explicit SKIP (not a
     * pass, not a failure). Use only when a harness limitation on the running version band prevents the
     * observation AND a substitute proof exists — cite it in {@code reason}.
     */
    public void skip(@NotNull String reason) {
        throw new SuiteSkip(reason);
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
