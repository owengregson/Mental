package me.vexmc.mental.v5.feature.loadout;

import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;

/**
 * SKELETON — Combat Test 8c melee reach ({@code ct8c-reach}, design spec §2.2 +
 * §2.11). Wave-2 <b>Task C</b> (the combat-core cluster) fills this body: the
 * 1.20.5+ interaction-range attribute + the {@code AttackRangeAdapter} component
 * path (base 2.5 / sword 3.0 / hoe+trident 3.5), with a ≤3.0 {@code
 * hit-registration} reach-validation enforcement and one loud degrade line for
 * 3.5 weapons below the floor. Also owns the targeting assists (§2.11) — the
 * 0.9 hitbox inflation and through-non-solid acceptance — implemented INSIDE
 * Mental's rewound reach validation, never by mutating entities (zero-touch).
 *
 * <p>Until then this is a genuine zero-touch no-op: the module defaults OFF, so
 * the reconciler never assembles it, and even assembled it registers nothing.</p>
 */
public final class Ct8cReachUnit implements FeatureUnit {

    @Override
    public Feature descriptor() {
        return Feature.CT8C_REACH;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        // Wave-2 (Task C) fills this. A scaffold registers nothing — zero-touch.
    }
}
