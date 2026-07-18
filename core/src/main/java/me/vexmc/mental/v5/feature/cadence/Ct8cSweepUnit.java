package me.vexmc.mental.v5.feature.cadence;

import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;

/**
 * SKELETON — Combat Test 8c sweep rules ({@code ct8c-sweep}, design spec §2.3).
 * Wave-2 <b>Task C</b> (the combat-core cluster) fills this body: sweep requires
 * a Sweeping-Edge ratio &gt; 0 AND {@code Ct8cChargeMath.sweepAllowed(scale)}
 * when {@code CHARGED_ATTACKS} is enabled (else enchant-gate only, decided at
 * assemble), ratios 0.25/0.333/0.375, not-crit/not-sprint-knock conditions, and
 * axe anvil eligibility via {@code PrepareAnvilEvent} — {@code SweepUnit} /
 * {@code SweepDamageListener} are the structural templates.
 *
 * <p>Until then this is a genuine zero-touch no-op: the module defaults OFF, so
 * the reconciler never assembles it, and even assembled it registers nothing.</p>
 */
public final class Ct8cSweepUnit implements FeatureUnit {

    @Override
    public Feature descriptor() {
        return Feature.CT8C_SWEEP;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        // Wave-2 (Task C) fills this. A scaffold registers nothing — zero-touch.
    }
}
