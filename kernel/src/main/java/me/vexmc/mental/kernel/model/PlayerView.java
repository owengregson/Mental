package me.vexmc.mental.kernel.model;

import java.util.UUID;
import me.vexmc.mental.kernel.math.Decay;
import me.vexmc.mental.kernel.profile.KnockbackProfile;

/**
 * One player's frozen per-tick snapshot — the ONLY thing netty-realm code may
 * read (spec §2). Published by the session at tick start, i.e. state as of the
 * END of the previous tick, so the mandate §4.3 boundary read holds by
 * construction. All fields are immutable values; cross-domain reads never touch
 * a live entity.
 */
public record PlayerView(UUID id, int entityId, TickStamp at,
                         Decay.Motion motion, boolean grounded, double slipperiness,
                         double gravity, double jumpImpulse, int jumpBoostAmplifier,
                         boolean sprinting, boolean creative, boolean pvpAllowed,
                         int noDamageTicks, int maxNoDamageTicks,
                         double knockbackResistance, boolean ocmOwnsMeleeKnockback,
                         KnockbackProfile profile, int pingMillis,
                         KinematicState kinematics) {

    /**
     * Mandate §4.3: the +1 staleness allowance — a boundary-cadence hit at
     * {@code max/2 + 1} is admitted (vanilla reads one tick lower at processing
     * and applies full damage), while sub-window spam stays immune.
     */
    public boolean damageImmune() {
        return noDamageTicks > maxNoDamageTicks / 2 + 1;
    }

    /**
     * A view older than 4 ticks is stale: exclusion turns off / the fast path
     * declines, preserving the {@link TickStamp#NO_TICK} degradation.
     */
    public boolean fresh(TickStamp now) {
        return at.recentAt(now, 4);
    }
}
