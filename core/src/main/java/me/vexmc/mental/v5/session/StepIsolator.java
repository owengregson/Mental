package me.vexmc.mental.v5.session;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs one throw-capable {@link SessionService#tick} step in isolation so a throw
 * in any one step can never starve the ledger decay / {@code PlayerView} publish
 * that follow it (2026-07-10-downward-kb-and-stacking-diagnoses.md, report 2 — the
 * latent HEAD hazard). All ledger decay rides the single per-player session tick,
 * whose several throw-capable steps run before the sole {@code ledger.tick} caller;
 * a starved {@code tickStep} freezes ledger decay, and a residual that never decays
 * is exactly the monotone stacking ramp diagnosed for the close-range spam blast.
 *
 * <p>Every failure is reported LOUDLY — never a silent degradation (B10) — but
 * rate-limited so a recurring per-tick throw does not bury the console: the first
 * occurrence per {@code (player, step)} logs at {@link Level#SEVERE} with the cause,
 * then further occurrences within the throttle window are counted and suppressed;
 * the next line past the window reports how many it suppressed, so the fault stays
 * visible without the 20-lines-a-second spam.</p>
 *
 * <p>A single instance is shared across every player's session tick. Ownership is
 * thread-safety: a given {@code (player, step)} key is only ever touched by that
 * player's owning region thread, so the per-key {@link Throttle} needs no locking;
 * the map itself is concurrent because different region threads insert distinct
 * keys.</p>
 */
public final class StepIsolator {

    private static final long DEFAULT_THROTTLE_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final Logger logger;
    private final LongSupplier nanos;
    private final long throttleNanos;
    private final ConcurrentHashMap<String, Throttle> throttles = new ConcurrentHashMap<>();

    /** The production isolator: the plugin logger, the wall clock, a 60-second throttle window. */
    public StepIsolator(Logger logger) {
        this(logger, System::nanoTime, DEFAULT_THROTTLE_NANOS);
    }

    /** The test seam: an injectable clock and throttle window make the rate-limiting deterministic. */
    StepIsolator(Logger logger, LongSupplier nanos, long throttleNanos) {
        this.logger = logger;
        this.nanos = nanos;
        this.throttleNanos = throttleNanos;
    }

    /**
     * Runs a side-effecting step, swallowing (and reporting) any {@link Throwable}
     * so the tick continues. Returns {@code true} when the body completed cleanly,
     * {@code false} when it threw — the tick reads nothing off it, but the boolean
     * makes the isolation contract testable.
     */
    public boolean run(UUID id, String step, Runnable body) {
        try {
            body.run();
            return true;
        } catch (Throwable failure) {
            record(id, step, failure);
            return false;
        }
    }

    /**
     * Runs a value-producing step (the two on the critical path — combat-grounded and
     * the view build), returning its value, or {@code fallback} when it threw. The
     * fallback keeps the tick flowing to {@code tickStep} rather than aborting the
     * whole tick body on a single step's fault.
     */
    public <T> T call(UUID id, String step, Supplier<T> body, T fallback) {
        try {
            return body.get();
        } catch (Throwable failure) {
            record(id, step, failure);
            return fallback;
        }
    }

    private void record(UUID id, String step, Throwable failure) {
        String key = step + '/' + id;
        Throttle throttle = throttles.computeIfAbsent(key, ignored -> new Throttle());
        long now = nanos.getAsLong();
        if (throttle.everLogged && now - throttle.lastLogNanos < throttleNanos) {
            throttle.suppressed++;
            return;
        }
        long suppressed = throttle.suppressed;
        throttle.suppressed = 0;
        throttle.lastLogNanos = now;
        throttle.everLogged = true;
        String message = "Mental session tick step '" + step + "' threw for player " + id
                + (suppressed > 0 ? " (" + suppressed + " suppressed since the last line)" : "")
                + " — ledger decay and the view publish are protected; this step's effect"
                + " was skipped this tick. This should never happen; please report it.";
        logger.log(Level.SEVERE, message, failure);
    }

    /** Per-(player, step) throttle state — mutated only by that player's owning thread. */
    private static final class Throttle {
        private boolean everLogged;
        private long lastLogNanos;
        private long suppressed;
    }
}
