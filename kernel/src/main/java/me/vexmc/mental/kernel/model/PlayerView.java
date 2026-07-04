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
                         KinematicState kinematics, double moveSpeedAttr) {

    /**
     * Additive growth: the 19-arg constructor defaults {@code moveSpeedAttr} to
     * {@link EntityState#MOVE_SPEED_UNAVAILABLE}. The netty fast path pre-sends
     * an attacker knock from the attacker's published view, so this field rides
     * the per-tick publish (single-writer) — the ONLY way a pre-sent knock can
     * see the attacker's movement speed and scale identically to the tick path
     * (one stamp, one truth). Views constructed without it (tests) resolve to
     * the stance baseline ⇒ pace factor 1.0.
     */
    public PlayerView(UUID id, int entityId, TickStamp at,
                      Decay.Motion motion, boolean grounded, double slipperiness,
                      double gravity, double jumpImpulse, int jumpBoostAmplifier,
                      boolean sprinting, boolean creative, boolean pvpAllowed,
                      int noDamageTicks, int maxNoDamageTicks,
                      double knockbackResistance, boolean ocmOwnsMeleeKnockback,
                      KnockbackProfile profile, int pingMillis,
                      KinematicState kinematics) {
        this(id, entityId, at, motion, grounded, slipperiness, gravity, jumpImpulse,
                jumpBoostAmplifier, sprinting, creative, pvpAllowed, noDamageTicks,
                maxNoDamageTicks, knockbackResistance, ocmOwnsMeleeKnockback, profile,
                pingMillis, kinematics, EntityState.MOVE_SPEED_UNAVAILABLE);
    }

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
