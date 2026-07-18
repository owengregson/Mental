package me.vexmc.mental.v5.feature.cadence;

import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;

/**
 * SKELETON — Combat Test 8c charged attacks ({@code charged-attacks}, design
 * spec §2.1). Wave-2 <b>Task C</b> (the combat-core cluster) fills this body: a
 * D2 per-player charge ledger (reset stamps from delivered attacks + air swings
 * via the parse rim's animation/attack taps) that denies damage below
 * {@code Ct8cChargeMath.attackAllowed}, feeds the charged reach extension to
 * reach validation, and publishes the charge (a {@code ChargeView}) for
 * {@code Ct8cSweepUnit} — netty reads published views only.
 *
 * <p>Until then this is a genuine zero-touch no-op: the module defaults OFF, so
 * the reconciler never assembles it, and even assembled it registers nothing.</p>
 */
public final class ChargedAttackUnit implements FeatureUnit {

    @Override
    public Feature descriptor() {
        return Feature.CHARGED_ATTACKS;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        // Wave-2 (Task C) fills this. A scaffold registers nothing — zero-touch.
    }
}
