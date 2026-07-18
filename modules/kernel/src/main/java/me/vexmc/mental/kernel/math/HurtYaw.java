package me.vexmc.mental.kernel.math;

/**
 * Vanilla's hurt-from yaw, in the victim's local frame:
 * {@code atan2(Δz, Δx) × 180/π − victimYaw} — the pure trigonometry
 * extracted from the hurt-animation sender.
 */
public final class HurtYaw {

    private HurtYaw() {}

    public static float hurtYaw(double attackerX, double attackerZ, double victimX, double victimZ, float victimYaw) {
        double dx = attackerX - victimX;
        double dz = attackerZ - victimZ;
        return (float) (Math.atan2(dz, dx) * (180.0 / Math.PI) - victimYaw);
    }
}
