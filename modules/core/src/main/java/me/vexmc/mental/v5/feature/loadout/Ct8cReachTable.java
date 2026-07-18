package me.vexmc.mental.v5.feature.loadout;

import me.vexmc.mental.kernel.math.Ct8cTables;
import me.vexmc.mental.v5.feature.cadence.Ct8cWeapons;

/**
 * Pure per-weapon CT8c melee reach (CT8c decompile, spec §2.2) — a Material NAME
 * maps, via the {@link Ct8cWeapons} classifier, onto the kernel {@link
 * Ct8cTables#reach} value: base 2.5, sword 3.0, hoe/trident 3.5. Bukkit-free, so
 * the table (and the below-floor degrade decision) is unit-testable without a
 * server.
 *
 * <p>The interaction-range attribute (1.20.5+) can express every value up to its
 * creative-range ceiling, so all three reaches apply on modern servers. Below the
 * attribute floor there is no safe per-player reach lever, and the fast path's
 * rewound reach validation only widens the gate up to 3.0; a 3.5-reach weapon
 * therefore cannot be honoured sub-floor — {@link #impossibleBelowFloor} flags it
 * for the loud boot-degrade line.</p>
 */
public final class Ct8cReachTable {

    /** The greatest reach the sub-floor path (fast-path reach validation) can enforce. */
    public static final double SUB_FLOOR_MAX_REACH = 3.0;

    private Ct8cReachTable() {}

    /** The CT8c reach for a held Material name (spec §2.2). */
    public static double reachFor(String materialName) {
        return Ct8cTables.reach(Ct8cWeapons.classify(materialName).weaponClass());
    }

    /** Whether {@code reach} exceeds what the sub-floor path can enforce (the 3.5 hoe/trident case). */
    public static boolean impossibleBelowFloor(double reach) {
        return reach > SUB_FLOOR_MAX_REACH;
    }
}
