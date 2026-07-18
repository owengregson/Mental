package me.vexmc.mental.v5.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The combat grounded-truth decision, unit-pinned. The regression this guards:
 * on the 1.9/1.10 NMS a packetless (clientless) player reads {@code
 * isOnGround()==false} forever after a send-then-restore knock while it rests on
 * the floor, so the combo grounded-run end and the servo precision inputs — which
 * used to read that raw flag — never saw the truth and a held combo never
 * released. The physical fallback keys off a solid surface under the feet with the
 * vertical settled (the same read the live {@code KnockbackSuite} pins against),
 * so the divergence is representable here without a live server.
 */
class CombatGroundTest {

    // A distance/velocity that satisfy "resting on the floor".
    private static final double ON_FLOOR = 0.0;
    private static final double SETTLED = 0.0;

    @Test
    void connectedClientTrustsTheFlagVerbatim() {
        // A real client is packet-FSM-fed: the flag is the era-correct source, so
        // the physical inputs are never consulted (passed as NaN here to prove it).
        assertTrue(CombatGround.grounded(true, true, Double.NaN, Double.NaN),
                "a connected, flag-grounded client is grounded");
        assertFalse(CombatGround.grounded(true, false, Double.NaN, Double.NaN),
                "a connected, flag-airborne client is airborne (flag is trusted, not overridden)");
    }

    @Test
    void packetlessFlagAirborneButOnTheFloorReadsGrounded() {
        // THE BUG SCENARIO: packetless, isOnGround()==false, but a solid surface is
        // directly under the feet and the vertical has settled → physically grounded.
        assertTrue(CombatGround.grounded(false, false, ON_FLOOR, SETTLED),
                "a packetless victim resting on the floor is grounded even though its flag lies airborne");
    }

    @Test
    void packetlessFlagGroundedIsGroundedWithoutTheFallback() {
        // On a modern NMS the packetless flag already reads grounded; that is trusted
        // directly (the physical inputs are irrelevant), so 1.9/1.10 now matches it.
        assertTrue(CombatGround.grounded(false, true, Double.NaN, Double.NaN),
                "a packetless, flag-grounded victim is grounded (modern-NMS behaviour)");
    }

    @Test
    void packetlessGenuinelyAirborneReadsAirborne() {
        // No solid surface under the feet, or a moving vertical: genuinely airborne.
        assertFalse(CombatGround.grounded(false, false, 1.5, SETTLED),
                "no ground under the feet ⇒ airborne");
        assertFalse(CombatGround.grounded(false, false, ON_FLOOR, 0.5),
                "a moving vertical ⇒ airborne even with ground under the feet");
    }

    @Test
    void theFloorEpsilonIsInclusiveAndTheSettleGateIsStrict() {
        assertTrue(CombatGround.grounded(false, false, CombatGround.GROUNDED_DISTANCE_EPSILON, SETTLED),
                "a foot-gap exactly at the epsilon still counts as standing");
        assertFalse(CombatGround.grounded(
                        false, false, CombatGround.GROUNDED_DISTANCE_EPSILON + 1.0e-6, SETTLED),
                "just past the epsilon is airborne");
        assertTrue(CombatGround.grounded(
                        false, false, ON_FLOOR, CombatGround.SETTLE_VELOCITY_EPSILON - 1.0e-6),
                "a vertical just under the settle epsilon is settled");
        assertFalse(CombatGround.grounded(false, false, ON_FLOOR, CombatGround.SETTLE_VELOCITY_EPSILON),
                "a vertical at the settle epsilon is not yet settled (strict)");
    }
}
