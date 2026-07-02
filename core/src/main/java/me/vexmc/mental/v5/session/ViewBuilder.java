package me.vexmc.mental.v5.session;

import java.util.UUID;
import me.vexmc.mental.kernel.math.Decay;
import me.vexmc.mental.kernel.model.KinematicState;
import me.vexmc.mental.kernel.model.PlayerView;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.kernel.profile.KnockbackProfile;

/**
 * Builds the immutable {@link PlayerView} the session publishes each tick — the
 * only thing netty-realm code may read (spec §2). The owning-thread reads live
 * outside this class ({@link SessionService} gathers them); the builder's one
 * responsibility is stamping the freshness tick from the clock, so a view always
 * carries {@code at = clock.current()} — the mandate §4.3 boundary-read contract.
 */
public final class ViewBuilder {

    private final TickClock clock;

    public ViewBuilder(TickClock clock) {
        this.clock = clock;
    }

    /**
     * Assembles a view from the already-read ingredients, stamping {@code at}
     * from the clock. Every ingredient is carried into the view 1:1 (the parser
     * of truth is the caller); the builder invents nothing but the tick stamp.
     */
    public PlayerView build(
            UUID id, int entityId,
            Decay.Motion motion, boolean grounded, double slipperiness,
            double gravity, double jumpImpulse, int jumpBoostAmplifier,
            boolean sprinting, boolean creative, boolean pvpAllowed,
            int noDamageTicks, int maxNoDamageTicks,
            double knockbackResistance, boolean ocmOwnsMeleeKnockback,
            KnockbackProfile profile, int pingMillis,
            KinematicState kinematics) {
        return new PlayerView(
                id, entityId, clock.current(), motion, grounded, slipperiness,
                gravity, jumpImpulse, jumpBoostAmplifier, sprinting, creative, pvpAllowed,
                noDamageTicks, maxNoDamageTicks, knockbackResistance, ocmOwnsMeleeKnockback,
                profile, pingMillis, kinematics);
    }
}
