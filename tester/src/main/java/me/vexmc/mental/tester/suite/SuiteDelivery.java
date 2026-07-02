package me.vexmc.mental.tester.suite;

import me.vexmc.mental.kernel.math.Decay;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.profile.KnockbackDelivery;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import org.jetbrains.annotations.Nullable;

/**
 * Mirrors the pipeline's wire delivery for suite expectations: a
 * TRACKER_DECAYED vector ships one victim physics tick late (the 1.7.10
 * later-joiner wire); TRACKER and IMMEDIATE ship as computed (the full
 * stamp — vanilla's tracker decayed only by connection order).
 *
 * <p>v5: the delivery decay is the kernel {@link Decay} authority (the retired
 * {@code VictimMotion.decayOnce}), so the suite math and the shipped values
 * share one source.</p>
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
        if (vector == null || delivery != KnockbackDelivery.TRACKER_DECAYED) {
            return vector;
        }
        Decay.Motion decayed = Decay.decayOnce(
                vector.x(), vector.y(), vector.z(), grounded, Decay.DEFAULT_GRAVITY);
        return new KnockbackVector(decayed.vx(), decayed.vy(), decayed.vz());
    }
}
