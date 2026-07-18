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

    /**
     * The full client-side simulation ({@code off-ground-sync true}) — the
     * backward-compatible default.
     *
     * @return the y override for this hit, or null when no correction applies.
     */
    public static Double verticalFor(PlayerView victim, int rttMillis, double baseVy) {
        return verticalFor(victim, rttMillis, baseVy, true);
    }

    /**
     * As above, but {@code off-ground-sync false} rewrites ONLY the on-ground /
     * landing-fold case and leaves an airborne victim at its raw ledger vertical
     * (the era baseline) — the documented opt-out from the airborne free-fall
     * simulation.
     *
     * @return the y override for this hit, or null when no correction applies.
     */
    public static Double verticalFor(PlayerView victim, int rttMillis, double baseVy, boolean offGroundSync) {
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
        // grounded — never at zero (mandate §4.2). This landing case is honored
        // regardless of off-ground-sync (it IS the on-ground case).
        int ticksToLand = MotionMath.ticksToFall(baseVy, victim.kinematics().distanceToGround(), gravity);
        if (ticksToLand >= 0 && ticksToLand <= ticks) {
            return Decay.groundedEquilibrium(gravity);
        }
        if (!offGroundSync) {
            // The documented opt-out: only the on-ground/landing case is rewritten;
            // the airborne victim keeps its raw ledger vertical (null = no override).
            return null;
        }
        // Still airborne when the hit lands: simulate the vertical forward.
        return MotionMath.simulateVerticalVelocity(baseVy, gravity, ticks);
    }
}
