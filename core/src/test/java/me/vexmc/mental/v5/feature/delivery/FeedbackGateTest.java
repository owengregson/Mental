package me.vexmc.mental.v5.feature.delivery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The per-victim velocity pre-send pacing: a hit inside the window is gated; a
 * hit past it consumes a fresh slot. A zero/negative window never gates (the
 * boundary-cadence combo relies on this — the {@code auto} window is
 * {@code (max/2 − 1)} ticks, never zero for a real hurt window).
 */
class FeedbackGateTest {

    private final UUID victim = UUID.randomUUID();

    @Test
    void gatesSecondSendInsideTheWindowAndAdmitsPastIt() {
        FeedbackGate gate = new FeedbackGate();
        assertTrue(gate.tryPreSend(victim, 1_000L, 100L), "first send always passes");
        assertFalse(gate.tryPreSend(victim, 1_050L, 100L), "50ms < 100ms window is gated");
        assertTrue(gate.tryPreSend(victim, 1_100L, 100L), "100ms reaches the window boundary");
    }

    @Test
    void zeroWindowNeverGates() {
        FeedbackGate gate = new FeedbackGate();
        assertTrue(gate.tryPreSend(victim, 0L, 0L));
        assertTrue(gate.tryPreSend(victim, 0L, 0L), "a zero window admits every hit");
    }

    @Test
    void forgetResetsTheWindow() {
        FeedbackGate gate = new FeedbackGate();
        assertTrue(gate.tryPreSend(victim, 1_000L, 100L));
        gate.forget(victim);
        assertTrue(gate.tryPreSend(victim, 1_010L, 100L), "a forgotten victim starts fresh");
    }
}
