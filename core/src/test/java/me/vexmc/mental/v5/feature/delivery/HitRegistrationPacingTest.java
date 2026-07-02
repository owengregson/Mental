package me.vexmc.mental.v5.feature.delivery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The B4 pacing contract at the {@link HitRegistrationUnit} pre-send seam: the
 * {@link FeedbackGate} paces the VELOCITY component ONLY. A velocity-suppressed
 * hurt-only burst never debits the budget, so it can never starve a later
 * eligible velocity pre-send; eligible velocities are still paced against one
 * another; a connectionless (pinned) victim bypasses the wire gate entirely.
 */
class HitRegistrationPacingTest {

    private final UUID victim = UUID.randomUUID();

    @Test
    void hurtOnlyBurstDoesNotDebitThePacingBudget() {
        FeedbackGate gate = new FeedbackGate();
        // A velocity-suppressed (hurt-only) hit: velocityEligible=false. It must
        // NOT consult the gate — no slot consumed, no debit.
        assertFalse(
                HitRegistrationUnit.admitVelocityPreSend(gate, victim, true, false, 1_000L, 100L),
                "a hurt-only burst never ships velocity");
        // An eligible-velocity hit 50ms later — inside the 100ms window — must
        // STILL pre-send velocity: the earlier hurt-only burst consumed no slot.
        assertTrue(
                HitRegistrationUnit.admitVelocityPreSend(gate, victim, true, true, 1_050L, 100L),
                "the eligible velocity pre-send must not be starved by the earlier hurt-only burst");
    }

    @Test
    void eligibleVelocitiesAreStillPacedAgainstEachOther() {
        FeedbackGate gate = new FeedbackGate();
        assertTrue(
                HitRegistrationUnit.admitVelocityPreSend(gate, victim, true, true, 1_000L, 100L),
                "the first eligible velocity always passes");
        assertFalse(
                HitRegistrationUnit.admitVelocityPreSend(gate, victim, true, true, 1_050L, 100L),
                "a second eligible velocity inside the window is paced");
        assertTrue(
                HitRegistrationUnit.admitVelocityPreSend(gate, victim, true, true, 1_100L, 100L),
                "an eligible velocity past the window admits a fresh slot");
    }

    @Test
    void connectionlessVictimIsPinnedAndBypassesTheWireGate() {
        FeedbackGate gate = new FeedbackGate();
        // No wire → the pre-send is pinned, never paced by the wire gate.
        assertTrue(HitRegistrationUnit.admitVelocityPreSend(gate, victim, false, true, 1_000L, 100L));
        assertTrue(HitRegistrationUnit.admitVelocityPreSend(gate, victim, false, true, 1_000L, 100L),
                "a connectionless victim is never gated by the wire pacing budget");
    }
}
