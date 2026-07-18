package me.vexmc.mental.v5.feature.damage;

import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;

/**
 * SKELETON — the Combat Test 8c Cleaving enchantment ({@code cleaving}, design
 * spec §2.9). Wave-2 <b>Task D</b> (the damage cluster) fills this body: a real
 * enchantment on modern Paper via the platform-seam registry injection
 * ({@code CleavingRegistrar}, probe-gated ~1.21.3+; a loud degrade below/on
 * failure). Damage +1+level folds through {@code Ct8cDamageUnit}'s composition;
 * the disable-scaling is consumed by {@code Ct8cShieldUnit} via a
 * {@code CleavingHandle}.
 *
 * <p>Until then this is a genuine zero-touch no-op: the module defaults OFF, so
 * the reconciler never assembles it, and even assembled it registers nothing.</p>
 */
public final class CleavingUnit implements FeatureUnit {

    @Override
    public Feature descriptor() {
        return Feature.CLEAVING;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        // Wave-2 (Task D) fills this. A scaffold registers nothing — zero-touch.
    }
}
