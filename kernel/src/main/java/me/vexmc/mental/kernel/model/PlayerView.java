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
                         KinematicState kinematics, double moveSpeedAttr,
                         UUID comboAttackerId,
                         double measuredVx, double measuredVz, float yaw,
                         double eyeHeight, int groundedTicks,
                         double yawRateDegPerTick) {

    /** The standing eye height above feet — the pocket-servo precision default (ReachValidator.EYE_HEIGHT). */
    private static final double DEFAULT_EYE_HEIGHT = 1.62;

    /**
     * The pre-target-v2 precision arity: carries the five §3.2b inputs but no
     * measured yaw rate. {@code yawRateDegPerTick} defaults to {@link Double#NaN}
     * (no source ⇒ the dynamic target's continuous turn term degrades to the
     * conservative 30°/tick floor — target-v2 repair #4), so a view built without
     * the yaw window is byte-identical to the pre-v2 solve.
     */
    public PlayerView(UUID id, int entityId, TickStamp at,
                      Decay.Motion motion, boolean grounded, double slipperiness,
                      double gravity, double jumpImpulse, int jumpBoostAmplifier,
                      boolean sprinting, boolean creative, boolean pvpAllowed,
                      int noDamageTicks, int maxNoDamageTicks,
                      double knockbackResistance, boolean ocmOwnsMeleeKnockback,
                      KnockbackProfile profile, int pingMillis,
                      KinematicState kinematics, double moveSpeedAttr,
                      UUID comboAttackerId,
                      double measuredVx, double measuredVz, float yaw,
                      double eyeHeight, int groundedTicks) {
        this(id, entityId, at, motion, grounded, slipperiness, gravity, jumpImpulse,
                jumpBoostAmplifier, sprinting, creative, pvpAllowed, noDamageTicks,
                maxNoDamageTicks, knockbackResistance, ocmOwnsMeleeKnockback, profile,
                pingMillis, kinematics, moveSpeedAttr, comboAttackerId,
                measuredVx, measuredVz, yaw, eyeHeight, groundedTicks, Double.NaN);
    }

    /**
     * The pre-precision-round arity (combo-hold §3.2): carries the {@code
     * comboAttackerId} but none of the §3.2b precision predictor inputs. The five
     * new components — the victim's measured per-tick velocity (the drift signal),
     * the published yaw and pose-aware eye height (dynamic target + packetless-safe
     * facing), and the consecutive grounded-tick count — default to their era-exact
     * no-ops (zero velocity, zero yaw, the standing eye, zero grounded run), so the
     * precision solve degrades to the base solve for any view built without them.
     */
    public PlayerView(UUID id, int entityId, TickStamp at,
                      Decay.Motion motion, boolean grounded, double slipperiness,
                      double gravity, double jumpImpulse, int jumpBoostAmplifier,
                      boolean sprinting, boolean creative, boolean pvpAllowed,
                      int noDamageTicks, int maxNoDamageTicks,
                      double knockbackResistance, boolean ocmOwnsMeleeKnockback,
                      KnockbackProfile profile, int pingMillis,
                      KinematicState kinematics, double moveSpeedAttr,
                      UUID comboAttackerId) {
        this(id, entityId, at, motion, grounded, slipperiness, gravity, jumpImpulse,
                jumpBoostAmplifier, sprinting, creative, pvpAllowed, noDamageTicks,
                maxNoDamageTicks, knockbackResistance, ocmOwnsMeleeKnockback, profile,
                pingMillis, kinematics, moveSpeedAttr, comboAttackerId,
                0.0, 0.0, 0.0f, DEFAULT_EYE_HEIGHT, 0);
    }

    /**
     * The attacker holding an ACTIVE combo against this player, or null when none
     * is (combo-hold §3.1) — the frozen truth the pocket-servo application gate
     * reads. The netty pre-send and the region compute both compare THIS hit's
     * attacker to this field: they scale the fresh knock only when they match, so
     * both realms read one frozen decision (the {@code moveSpeedAttr} additive
     * precedent). Null in every construction that predates the servo (the 20-arg
     * constructor), i.e. the module off / no active combo ⇒ σ = 1.0.
     */
    public PlayerView(UUID id, int entityId, TickStamp at,
                      Decay.Motion motion, boolean grounded, double slipperiness,
                      double gravity, double jumpImpulse, int jumpBoostAmplifier,
                      boolean sprinting, boolean creative, boolean pvpAllowed,
                      int noDamageTicks, int maxNoDamageTicks,
                      double knockbackResistance, boolean ocmOwnsMeleeKnockback,
                      KnockbackProfile profile, int pingMillis,
                      KinematicState kinematics, double moveSpeedAttr) {
        this(id, entityId, at, motion, grounded, slipperiness, gravity, jumpImpulse,
                jumpBoostAmplifier, sprinting, creative, pvpAllowed, noDamageTicks,
                maxNoDamageTicks, knockbackResistance, ocmOwnsMeleeKnockback, profile,
                pingMillis, kinematics, moveSpeedAttr, null);
    }

    /**
     * The attacker's WALK-STANCE-NORMALIZED movement-speed attribute (the ×1.3
     * sprint modifier stripped at capture) for speed-conformal knockback.
     *
     * <p>Additive growth: the 19-arg constructor defaults {@code moveSpeedAttr} to
     * {@link EntityState#MOVE_SPEED_UNAVAILABLE} and {@code comboAttackerId} to
     * null. The netty fast path pre-sends an attacker knock from the attacker's
     * published view, so this field rides the per-tick publish (single-writer) —
     * the ONLY way a pre-sent knock can see the attacker's movement speed and
     * scale identically to the tick path (one stamp, one truth). Views constructed
     * without it (tests) resolve to the walk baseline ⇒ pace factor 1.0.</p>
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
                pingMillis, kinematics, EntityState.MOVE_SPEED_UNAVAILABLE, null);
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
