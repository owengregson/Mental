package me.vexmc.mental.tester.suite;

import me.vexmc.mental.config.KnockbackDelivery;
import me.vexmc.mental.config.KnockbackProfile;
import me.vexmc.mental.module.knockback.KnockbackVector;
import me.vexmc.mental.module.knockback.VictimMotion;
import org.jetbrains.annotations.Nullable;

/**
 * Mirrors the pipeline's wire delivery for suite expectations: a
 * TRACKER-delivered vector ships one victim physics tick late (the measured
 * 1.7.10 wire), an IMMEDIATE one ships as computed (1.8.9 melee).
 */
final class SuiteDelivery {

    private SuiteDelivery() {}

    static @Nullable KnockbackVector melee(
            @Nullable KnockbackVector vector, KnockbackProfile profile, boolean victimGrounded) {
        return adjust(vector, profile.meleeDelivery(), victimGrounded);
    }

    static @Nullable KnockbackVector projectile(
            @Nullable KnockbackVector vector, KnockbackProfile profile, boolean victimGrounded) {
        return adjust(vector, profile.projectileDelivery(), victimGrounded);
    }

    private static @Nullable KnockbackVector adjust(
            @Nullable KnockbackVector vector, KnockbackDelivery delivery, boolean grounded) {
        if (vector == null || delivery != KnockbackDelivery.TRACKER) {
            return vector;
        }
        VictimMotion.Motion decayed = VictimMotion.decayOnce(
                vector.x(), vector.y(), vector.z(), grounded, VictimMotion.DEFAULT_GRAVITY);
        return new KnockbackVector(decayed.vx(), decayed.vy(), decayed.vz());
    }
}
