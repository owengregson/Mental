package me.vexmc.mental.v5.feature.delivery;

import me.vexmc.mental.kernel.math.Ct8cTables;
import me.vexmc.mental.v5.feature.loadout.Ct8cReachTable;

/**
 * The CT8c reach the fast path's rewound reach validation enforces when {@code
 * ct8c-reach} is enabled (Task INT wire 2a/2b) — the per-weapon max from {@link
 * Ct8cReachTable} (spec §2.2: base 2.5 / sword 3.0 / hoe+trident 3.5), plus the
 * {@code +1.0} a ≥195% charged hit earns (spec §2.1) when {@code charged-attacks}
 * is also on. Pure, so the selection and the charged composition are unit-pinned
 * apart from the live gate.
 *
 * <h2>Why the netty gate uses the ceiling, not the exact per-weapon reach</h2>
 *
 * <p>The reach gate runs on the attacker's netty thread, which may not read the
 * attacker's live inventory (Folia {@code ensureTickThread}), and the held-weapon
 * reach is not among the values frozen into the published {@code PlayerView}. So
 * the gate calls {@link #gateReach} with a {@code null} material and gets the
 * per-weapon {@link #weaponCeiling()} (3.5). That is exactly right for what this
 * gate is — a lenient blatant-reach filter, "not an anticheat", where borderline
 * hits always land: it must never over-reject a legit long-reach weapon, and honest
 * per-weapon precision is delivered on modern servers by {@code Ct8cReachUnit}'s
 * client-synced {@code ENTITY_INTERACTION_RANGE} attribute (the client self-limits
 * per weapon). The per-weapon selection path is kept for tests and for any future
 * caller that can supply the material on a region thread.</p>
 */
public final class Ct8cReachGate {

    /** The reach a ≥195% charged hit adds (spec §2.1) — {@code Ct8cChargeView} publishes the flag. */
    public static final double CHARGED_REACH_BONUS = 1.0;

    private Ct8cReachGate() {}

    /** The greatest CT8c per-weapon reach (the 3.5 hoe/trident) — the netty gate's weapon-blind bound. */
    public static double weaponCeiling() {
        double max = 0.0;
        for (Ct8cTables.WeaponClass weapon : Ct8cTables.WeaponClass.values()) {
            max = Math.max(max, Ct8cTables.reach(weapon));
        }
        return max;
    }

    /**
     * The reach the gate enforces this hit: the CT8c reach for {@code heldMaterialName}
     * (or the {@link #weaponCeiling()} when it is {@code null} — the weapon-blind netty
     * path), plus {@link #CHARGED_REACH_BONUS} when {@code chargedReach} is set.
     */
    public static double gateReach(String heldMaterialName, boolean chargedReach) {
        double base = heldMaterialName == null
                ? weaponCeiling()
                : Ct8cReachTable.reachFor(heldMaterialName);
        return base + (chargedReach ? CHARGED_REACH_BONUS : 0.0);
    }
}
