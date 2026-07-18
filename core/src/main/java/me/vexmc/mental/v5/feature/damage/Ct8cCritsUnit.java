package me.vexmc.mental.v5.feature.damage;

import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;

/**
 * SKELETON — Combat Test 8c critical hits ({@code ct8c-crits}, design spec §2.3).
 * Wave-2 <b>Task D</b> (the damage cluster) fills this body: the CT8c crit policy
 * (sprinting allowed, enchant damage folded into the base BEFORE the flat ×1.5)
 * — mirroring {@code CritFallbackUnit}, with {@code DamageShaper.composeLegacy}
 * gaining a CT8c ordering variant.
 *
 * <p>Until then this is a genuine zero-touch no-op: the module defaults OFF, so
 * the reconciler never assembles it, and even assembled it registers nothing.</p>
 */
public final class Ct8cCritsUnit implements FeatureUnit {

    @Override
    public Feature descriptor() {
        return Feature.CT8C_CRITS;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        // Wave-2 (Task D) fills this. A scaffold registers nothing — zero-touch.
    }
}
