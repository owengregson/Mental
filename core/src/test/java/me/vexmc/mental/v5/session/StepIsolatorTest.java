package me.vexmc.mental.v5.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * The session-tick step armor (2026-07-10-downward-kb-and-stacking-diagnoses.md,
 * report 2 — the latent HEAD hazard): all ledger decay rides the single per-player
 * SessionService.tick, whose several throw-capable steps run before the sole
 * ledger.tick caller. A recurring throw in any one would starve ledger decay
 * forever — a residual that never decays is exactly the monotone stacking ramp. The
 * isolator runs each step in isolation so a throw in one never prevents the decay /
 * publish step that follows, and reports every failure loudly but rate-limited
 * (B10: no silent degradation).
 */
class StepIsolatorTest {

    private static final UUID ID = UUID.randomUUID();

    private static Logger recordingLogger(List<LogRecord> sink) {
        Logger logger = Logger.getLogger("StepIsolatorTest-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) {
                sink.add(record);
            }
            @Override public void flush() {}
            @Override public void close() {}
        });
        return logger;
    }

    private static long severeCount(List<LogRecord> records) {
        return records.stream().filter(r -> r.getLevel() == Level.SEVERE).count();
    }

    @Test
    void aThrowingStepDoesNotPreventTheDecayStep() {
        List<LogRecord> logs = new CopyOnWriteArrayList<>();
        StepIsolator isolator = new StepIsolator(recordingLogger(logs));

        // The hazard shape: an earlier tick step throws every tick.
        boolean firstOk = isolator.run(ID, "sampleGround", () -> {
            throw new RuntimeException("recurring boom");
        });
        // The step the whole armor exists to guarantee: ledger decay / the publish.
        boolean[] decayRan = {false};
        boolean decayOk = isolator.run(ID, "tickStep", () -> decayRan[0] = true);

        assertFalse(firstOk, "the throwing step reports failure, does not propagate");
        assertTrue(decayOk, "the decay step ran despite the earlier throw");
        assertTrue(decayRan[0], "ledger decay is never starved by an upstream throw");
    }

    @Test
    void aFailureIsLoggedSevereWithStepAndCause() {
        List<LogRecord> logs = new CopyOnWriteArrayList<>();
        StepIsolator isolator = new StepIsolator(recordingLogger(logs));

        RuntimeException boom = new RuntimeException("boom");
        isolator.run(ID, "driveCombo", () -> {
            throw boom;
        });

        assertEquals(1, severeCount(logs), "a first failure is loud (SEVERE)");
        LogRecord record = logs.get(0);
        assertTrue(record.getMessage().contains("driveCombo"), "names the failing step");
        assertSame(boom, record.getThrown(), "carries the cause for the stack trace");
    }

    @Test
    void callReturnsTheValueOrTheFallbackOnThrow() {
        List<LogRecord> logs = new CopyOnWriteArrayList<>();
        StepIsolator isolator = new StepIsolator(recordingLogger(logs));

        String ok = isolator.call(ID, "combatGrounded", () -> "computed", "fallback");
        assertEquals("computed", ok, "a clean step returns its value");

        String bad = isolator.call(ID, "buildView", () -> {
            throw new RuntimeException("view boom");
        }, "fallback");
        assertEquals("fallback", bad, "a throwing step yields the caller's fallback, not a propagated throw");
        assertEquals(1, severeCount(logs), "the value-step failure is logged too");
    }

    @Test
    void repeatedFailuresAreThrottledThenReLoggedAfterTheWindow() {
        List<LogRecord> logs = new CopyOnWriteArrayList<>();
        long[] now = {0L};
        // Deterministic clock + a 1000-nano throttle window.
        StepIsolator isolator = new StepIsolator(recordingLogger(logs), () -> now[0], 1000L);

        Runnable throwing = () -> {
            throw new RuntimeException("spam");
        };
        isolator.run(ID, "ensureStranded", throwing); // first sight -> logs
        isolator.run(ID, "ensureStranded", throwing); // within window -> suppressed
        isolator.run(ID, "ensureStranded", throwing); // within window -> suppressed
        assertEquals(1, severeCount(logs), "a recurring throw does not spam the console every tick");

        now[0] = 5_000L; // past the throttle window
        isolator.run(ID, "ensureStranded", throwing); // logs again
        assertEquals(2, severeCount(logs), "the window elapsing re-logs so the fault stays visible");
        LogRecord second = logs.get(1);
        assertNotNull(second.getMessage());
        assertTrue(second.getMessage().contains("2") || second.getMessage().toLowerCase().contains("suppress"),
                "the re-log reports the suppressed occurrences since the last line");
    }

    @Test
    void distinctStepsThrottleIndependently() {
        List<LogRecord> logs = new CopyOnWriteArrayList<>();
        long[] now = {0L};
        StepIsolator isolator = new StepIsolator(recordingLogger(logs), () -> now[0], 1000L);

        isolator.run(ID, "stepA", () -> { throw new RuntimeException(); });
        isolator.run(ID, "stepB", () -> { throw new RuntimeException(); });

        assertEquals(2, severeCount(logs), "each step keeps its own throttle state");
    }
}
