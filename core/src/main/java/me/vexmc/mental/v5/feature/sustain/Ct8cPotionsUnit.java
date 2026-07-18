package me.vexmc.mental.v5.feature.sustain;

import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;

/**
 * SKELETON — Combat Test 8c potion values ({@code ct8c-potions}, design spec
 * §2.8). Wave-2 <b>Task F</b> (the sustain cluster) fills this body: intercept
 * Instant Health/Damage to the CT8c values ({@code 6·2^amp}, the
 * {@code PotionValuesUnit} substitution pattern), feed Strength/Weakness
 * ±20%/level into the {@code DamageShaper} composition (this unit consumes
 * {@code Ct8cPotionMath} and owns no shaper edits), and scale tipped-arrow
 * instantaneous effects ×1/8.
 *
 * <p>Until then this is a genuine zero-touch no-op: the module defaults OFF, so
 * the reconciler never assembles it, and even assembled it registers nothing.</p>
 */
public final class Ct8cPotionsUnit implements FeatureUnit {

    @Override
    public Feature descriptor() {
        return Feature.CT8C_POTIONS;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        // Wave-2 (Task F) fills this. A scaffold registers nothing — zero-touch.
    }
}
