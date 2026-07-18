package me.vexmc.mental.v5.feature.cadence;

import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;

/**
 * SKELETON — Combat Test 8c weapon attack speeds ({@code weapon-attack-speeds},
 * design spec §2.2). Wave-2 <b>Task C</b> (the combat-core cluster) fills this
 * body: recompute the held item's ATTACK_SPEED attribute from the CT8c
 * attacks-per-second table on join/respawn/world-change/held-slot/item change
 * (the HitboxUnit reconcile pattern; attribute value = {@code attacksPerSecond +
 * 1.5}), with a captured-base restore on disable/quit, arbitrating against
 * {@code ATTACK_COOLDOWN}'s 1024 spoof.
 *
 * <p>Until then this is a genuine zero-touch no-op: the module defaults OFF, so
 * the reconciler never assembles it, and even assembled it registers nothing.</p>
 */
public final class WeaponSpeedUnit implements FeatureUnit {

    @Override
    public Feature descriptor() {
        return Feature.WEAPON_ATTACK_SPEEDS;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        // Wave-2 (Task C) fills this. A scaffold registers nothing — zero-touch.
    }
}
