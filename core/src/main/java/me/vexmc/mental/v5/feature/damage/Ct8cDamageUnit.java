package me.vexmc.mental.v5.feature.damage;

import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;

/**
 * SKELETON — Combat Test 8c weapon damage tables ({@code ct8c-damage}, design
 * spec §2.2). Wave-2 <b>Task D</b> (the damage cluster) fills this body: compose
 * the CT8c damage via the {@code DamageShaper} seam (a new {@code
 * ct8cToolBase(weapon)} alongside {@code eraToolBase}, numbers from {@code
 * Ct8cTables.damage}; fist 2 baseline, tiers one lower than vanilla, hoe/trident
 * flats). Cleaving's +1+level and Strength/Weakness fold through this same
 * composition.
 *
 * <p>Until then this is a genuine zero-touch no-op: the module defaults OFF, so
 * the reconciler never assembles it, and even assembled it registers nothing.</p>
 */
public final class Ct8cDamageUnit implements FeatureUnit {

    @Override
    public Feature descriptor() {
        return Feature.CT8C_DAMAGE;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        // Wave-2 (Task D) fills this. A scaffold registers nothing — zero-touch.
    }
}
