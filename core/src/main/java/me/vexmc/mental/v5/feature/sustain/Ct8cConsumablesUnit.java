package me.vexmc.mental.v5.feature.sustain;

import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;

/**
 * SKELETON — Combat Test 8c consumables ({@code ct8c-consumables}, design spec
 * §2.7/§2.8). Wave-2 <b>Task F</b> (the sustain cluster) fills this body: 20-tick
 * drink durations, drinkable potions stacking to 16, and snowballs to 64 — via
 * the 1.20.5+ item-component seam (an {@code AttackRangeAdapter}-style {@code
 * Ct8cConsumableAdapter} in {@code v5/platform/}), with a loud boot degrade below
 * that floor.
 *
 * <p>Until then this is a genuine zero-touch no-op: the module defaults OFF, so
 * the reconciler never assembles it, and even assembled it registers nothing.</p>
 */
public final class Ct8cConsumablesUnit implements FeatureUnit {

    @Override
    public Feature descriptor() {
        return Feature.CT8C_CONSUMABLES;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        // Wave-2 (Task F) fills this. A scaffold registers nothing — zero-touch.
    }
}
