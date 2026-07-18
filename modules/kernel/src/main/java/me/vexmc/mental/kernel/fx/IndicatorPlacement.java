package me.vexmc.mental.kernel.fx;

import java.util.Random;

/**
 * Places a damage indicator on the front half of a ring around the victim —
 * the half facing the attacker, so the popping text lands in the attacker's
 * view. Pure math: the caller supplies positions and randomness; nothing here
 * knows about entities or packets (kernel Bukkit-free invariant).
 *
 * <p>Bearing = the victim→attacker azimuth ± up to 90°; a zero horizontal
 * distance (attacker exactly on the victim column) substitutes azimuth 0 so
 * the placement never goes NaN. Height = feet + chestOffset ± heightJitter,
 * uniform.</p>
 */
public final class IndicatorPlacement {

    /** Where the indicator text spawns, and the ring bearing it popped along. */
    public record Spawn(double x, double y, double z, double bearing) {}

    private IndicatorPlacement() {}

    public static Spawn place(
            double victimX, double victimY, double victimZ,
            double attackerX, double attackerZ,
            double ringRadius, double chestOffset, double heightJitter,
            Random random) {
        double dx = attackerX - victimX;
        double dz = attackerZ - victimZ;
        double azimuth = (dx == 0.0 && dz == 0.0) ? 0.0 : Math.atan2(dz, dx);
        double bearing = azimuth + (random.nextDouble() - 0.5) * Math.PI;
        double y = victimY + chestOffset + (random.nextDouble() * 2.0 - 1.0) * heightJitter;
        return new Spawn(
                victimX + ringRadius * Math.cos(bearing),
                y,
                victimZ + ringRadius * Math.sin(bearing),
                bearing);
    }
}
