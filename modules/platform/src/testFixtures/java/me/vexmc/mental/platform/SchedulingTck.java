package me.vexmc.mental.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

/**
 * The {@link Scheduling} conformance suite (spec §12.4) — a backend-agnostic set
 * of behavioural cases every implementation must pass. It is written entirely
 * against the {@code Scheduling} contract plus a handful of abstract driver
 * hooks a backend fills in, so the same suite proves {@code BukkitScheduling}
 * (unit, stubbed scheduler) and — once its region tick can be driven —
 * {@code FoliaScheduling} (Task 5.6, live).
 *
 * <p>The retired-callback contract on {@link Scheduling} is the star case: it
 * asserts only the honest common denominator the two backends share (retired
 * fires exactly once, thread affinity is not assumed), never the accidents of
 * one backend's timing.</p>
 *
 * <h2>Driver hooks</h2>
 * <p>A subclass supplies the backend under test and a way to advance its clock.
 * The suite never blocks on wall time: it schedules work, {@link #tick()}s the
 * backend a controlled number of times, and asserts. {@link #tick()} MUST run
 * every due sync/one-shot task and advance every live timer by one period, and
 * MUST NOT return until that tick's work has completed (so assertions can read
 * results synchronously). {@link #awaitAsyncIdle()} blocks until async work
 * submitted so far has run.</p>
 */
public abstract class SchedulingTck {

    /** The backend under test. A fresh, empty scheduler per test is expected. */
    protected abstract @NotNull Scheduling scheduling();

    /**
     * Advances the backend one tick: runs every due one-shot/sync task and fires
     * every live timer once, and blocks until that work has completed.
     */
    protected abstract void tick();

    /** Blocks until every async task submitted so far has finished. */
    protected abstract void awaitAsyncIdle();

    /** An entity the owning thread owns and whose {@code isValid()} is {@code true}. */
    protected abstract @NotNull Entity ownedEntity();

    /** An entity whose {@code isValid()} is {@code false} — must trigger {@code retired}. */
    protected abstract @NotNull Entity retiredEntity();

    /** True iff the calling thread is the owning/main thread sync tasks run on. */
    protected abstract boolean onOwningThread();

    @Test
    void runOnValidEntityRunsTheTaskOnTheOwningThreadAndNeverRetired() {
        AtomicInteger tasks = new AtomicInteger();
        AtomicInteger retired = new AtomicInteger();
        AtomicBoolean owningThread = new AtomicBoolean();

        scheduling().runOn(
                ownedEntity(),
                () -> {
                    owningThread.set(onOwningThread());
                    tasks.incrementAndGet();
                },
                retired::incrementAndGet);
        tick();

        assertEquals(1, tasks.get(), "the task must run once for a valid entity");
        assertEquals(0, retired.get(), "retired must not fire for a valid entity");
        assertTrue(owningThread.get(), "the task must run on the owning thread");
    }

    @Test
    void runOnRetiredEntityFiresRetiredExactlyOnceAndNeverTheTask() {
        AtomicInteger tasks = new AtomicInteger();
        AtomicInteger retired = new AtomicInteger();

        scheduling().runOn(retiredEntity(), tasks::incrementAndGet, retired::incrementAndGet);
        // Several ticks: a one-shot that retired must never re-fire on a later tick.
        tick();
        tick();
        tick();

        assertEquals(0, tasks.get(), "the task must not run for a retired entity");
        assertEquals(1, retired.get(), "retired must fire exactly once");
    }

    @Test
    void ensureOnAnOwnedLiveEntityRunsTheTaskInlineWithoutATick() {
        AtomicInteger tasks = new AtomicInteger();
        AtomicInteger retired = new AtomicInteger();

        // The caller owns the entity, so ensureOn runs the task synchronously —
        // no tick() drains a queue, unlike runOn.
        scheduling().ensureOn(ownedEntity(), tasks::incrementAndGet, retired::incrementAndGet);

        assertEquals(1, tasks.get(), "an owned live entity runs the task inline, before any tick");
        assertEquals(0, retired.get(), "an owned live entity never retires");
    }

    @Test
    void ensureOnARetiredEntityDefersAndFiresRetiredExactlyOnce() {
        AtomicInteger tasks = new AtomicInteger();
        AtomicInteger retired = new AtomicInteger();

        scheduling().ensureOn(retiredEntity(), tasks::incrementAndGet, retired::incrementAndGet);
        tick();
        tick();

        assertEquals(0, tasks.get(), "a retired entity never runs the task");
        assertEquals(1, retired.get(), "retired fires exactly once via the deferred path");
    }

    @Test
    void repeatOnStopsAfterTheHandleIsCancelled() {
        AtomicInteger runs = new AtomicInteger();

        TaskHandle handle = scheduling().repeatOn(ownedEntity(), 0, 1, runs::incrementAndGet, () -> {});
        tick();
        tick();
        int beforeCancel = runs.get();
        assertTrue(beforeCancel >= 1, "the repeating task must fire while live");
        assertFalse(handle.cancelled());

        handle.cancel();
        assertTrue(handle.cancelled(), "cancel() must be observable via cancelled()");
        tick();
        tick();

        assertEquals(beforeCancel, runs.get(), "no further runs may occur after cancel");
    }

    @Test
    void repeatGlobalFiresPeriodicallyUntilCancelled() {
        AtomicInteger runs = new AtomicInteger();

        TaskHandle handle = scheduling().repeatGlobal(0, 1, runs::incrementAndGet);
        tick();
        tick();
        tick();
        int afterThree = runs.get();
        assertTrue(afterThree >= 2, "a period-1 timer must fire on successive ticks (periodicity)");

        handle.cancel();
        tick();
        assertEquals(afterThree, runs.get(), "a cancelled timer must not fire again");
    }

    @Test
    void runAsyncRunsOffTheOwningThread() {
        AtomicBoolean offOwningThread = new AtomicBoolean();
        AtomicInteger runs = new AtomicInteger();

        scheduling().runAsync(() -> {
            offOwningThread.set(!onOwningThread());
            runs.incrementAndGet();
        });
        awaitAsyncIdle();

        assertEquals(1, runs.get(), "the async task must run once");
        assertTrue(offOwningThread.get(), "runAsync must not run on the owning thread");
    }
}
