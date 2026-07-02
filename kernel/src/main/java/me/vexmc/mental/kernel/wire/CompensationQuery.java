package me.vexmc.mental.kernel.wire;

import me.vexmc.mental.kernel.math.Decay;
import me.vexmc.mental.kernel.math.MotionMath;
import me.vexmc.mental.kernel.model.PlayerView;

/**
 * B11's answer: a stateless per-hit vertical compensation query (spec §5).
 * Given the victim's published view, the round-trip time, and the base vertical
 * velocity, it predicts the victim's vertical where the hit will actually apply
 * — one continuous signed simulation over {@code rtt/50} ticks (no
 * rise-glued-to-fall re-seed). N combo hits get N independent answers; there is
 * no slot or TTL.
 */
public final class CompensationQuery {

    private static final int MILLIS_PER_TICK = 50;

    private CompensationQuery() {}

    /** @return the y override for this hit, or null when no correction applies. */
    public static Double verticalFor(PlayerView victim, int rttMillis, double baseVy) {
        // The double-hit guard: a victim hit within the last few ticks is not
        // re-compensated (the ledger already carries the flight residual).
        if (victim.noDamageTicks() > 8) {
            return null;
        }
        // An on-ground victim needs no prediction — its motion is already the
        // grounded equilibrium.
        if (victim.kinematics().clientOnGround()) {
            return null;
        }
        int ticks = rttMillis / MILLIS_PER_TICK;
        double gravity = victim.gravity();
        // Will the victim have landed by the time the hit applies? One continuous
        // signed fold; if it reaches the ground inside the window, the hit lands
        // grounded — never at zero (mandate §4.2).
        int ticksToLand = MotionMath.ticksToFall(baseVy, victim.kinematics().distanceToGround(), gravity);
        if (ticksToLand >= 0 && ticksToLand <= ticks) {
            return Decay.groundedEquilibrium(gravity);
        }
        // Still airborne when the hit lands: simulate the vertical forward.
        return MotionMath.simulateVerticalVelocity(baseVy, gravity, ticks);
    }
}
