package me.vexmc.mental.v5.feature.cadence;

/**
 * The Combat Test 8c Sweeping-Edge ratio (CT8c decompile, spec §2.3) —
 * {@code 0.5 − 0.5/(level+1)}, so level 1/2/3 give 0.25 / 0.333… / 0.375. A ratio
 * of {@code 0} (no Sweeping Edge) means no sweep at all: plain swords no longer
 * sweep in 8c, the stricter gate {@code Ct8cSweepUnit} enforces. The secondary
 * sweep damage is {@code 1 + ratio·mainDamage}.
 */
public final class Ct8cSweepRatios {

    private Ct8cSweepRatios() {}

    /** The sweep ratio for a Sweeping-Edge {@code level} ({@code ≤ 0} ⇒ {@code 0}, no sweep). */
    public static double ratio(int level) {
        if (level <= 0) {
            return 0.0;
        }
        return 0.5 - 0.5 / (level + 1);
    }

    /** The secondary sweep damage dealt to each other target: {@code 1 + ratio·mainDamage} (spec §2.3). */
    public static double secondaryDamage(int sweepingEdgeLevel, double mainDamage) {
        return 1.0 + ratio(sweepingEdgeLevel) * mainDamage;
    }
}
