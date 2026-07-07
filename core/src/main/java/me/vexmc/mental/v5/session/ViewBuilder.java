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
            double knockbackResistance,
            KnockbackProfile profile, int pingMillis,
            KinematicState kinematics, double moveSpeedAttr) {
        return build(id, entityId, motion, grounded, slipperiness, gravity, jumpImpulse,
                jumpBoostAmplifier, sprinting, creative, pvpAllowed, noDamageTicks,
                maxNoDamageTicks, knockbackResistance, profile,
                pingMillis, kinematics, moveSpeedAttr, null);
    }

    /**
     * The combo-hold overload — carries the {@code comboAttackerId} (the attacker
     * holding an active combo against this player, or null) into the view so the
     * pocket-servo application gate reads one frozen truth. The pre-servo overload
     * defaults it to null (module off / no active combo ⇒ σ = 1.0).
     */
    public PlayerView build(
            UUID id, int entityId,
            Decay.Motion motion, boolean grounded, double slipperiness,
            double gravity, double jumpImpulse, int jumpBoostAmplifier,
            boolean sprinting, boolean creative, boolean pvpAllowed,
            int noDamageTicks, int maxNoDamageTicks,
            double knockbackResistance,
            KnockbackProfile profile, int pingMillis,
            KinematicState kinematics, double moveSpeedAttr, UUID comboAttackerId) {
        return new PlayerView(
                id, entityId, clock.current(), motion, grounded, slipperiness,
                gravity, jumpImpulse, jumpBoostAmplifier, sprinting, creative, pvpAllowed,
                noDamageTicks, maxNoDamageTicks, knockbackResistance,
                profile, pingMillis, kinematics, moveSpeedAttr, comboAttackerId);
    }

    /**
     * The pocket-servo precision overload (combo-hold §3.2b) — carries the five
     * predictor inputs the netty pre-send reads for the precision solve: the
     * victim's measured per-tick velocity (the drift signal, frozen at the SAME
     * publish as the ledger residual so their difference is coherent), the published
     * yaw and pose-aware eye height (the dynamic target's facing and geometry,
     * packetless-safe where the connection-domain yaw wire is absent), the
     * consecutive grounded-tick count, and the measured yaw rate (the V2 turn
     * term's divisor — target-v2 repair #4). The pre-precision overload defaults
     * them to era-exact no-ops, so a view built without them degrades to the base solve.
     */
    public PlayerView build(
            UUID id, int entityId,
            Decay.Motion motion, boolean grounded, double slipperiness,
            double gravity, double jumpImpulse, int jumpBoostAmplifier,
            boolean sprinting, boolean creative, boolean pvpAllowed,
            int noDamageTicks, int maxNoDamageTicks,
            double knockbackResistance,
            KnockbackProfile profile, int pingMillis,
            KinematicState kinematics, double moveSpeedAttr, UUID comboAttackerId,
            double measuredVx, double measuredVz, float yaw, double eyeHeight, int groundedTicks,
            double yawRateDegPerTick) {
        return new PlayerView(
                id, entityId, clock.current(), motion, grounded, slipperiness,
                gravity, jumpImpulse, jumpBoostAmplifier, sprinting, creative, pvpAllowed,
                noDamageTicks, maxNoDamageTicks, knockbackResistance,
                profile, pingMillis, kinematics, moveSpeedAttr, comboAttackerId,
                measuredVx, measuredVz, yaw, eyeHeight, groundedTicks, yawRateDegPerTick);
    }
}
