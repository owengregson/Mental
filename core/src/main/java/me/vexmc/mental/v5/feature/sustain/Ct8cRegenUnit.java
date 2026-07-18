package me.vexmc.mental.v5.feature.sustain;

import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;

/**
 * SKELETON — Combat Test 8c food/regen ({@code ct8c-regen}, design spec §2.7).
 * Wave-2 <b>Task F</b> (the sustain cluster) fills this body, mirroring {@code
 * RegenUnit} with {@code Ct8cRegenMath}: heal 1 HP every 40 ticks while
 * {@code foodLevel > 6} (50% chance to drain 1 hunger per heal), starvation
 * cadence, sprint gated on {@code foodLevel > 6}, and eating/drinking
 * interrupted when hit by a player or mob.
 *
 * <p>Until then this is a genuine zero-touch no-op: the module defaults OFF, so
 * the reconciler never assembles it, and even assembled it registers nothing.</p>
 */
public final class Ct8cRegenUnit implements FeatureUnit {

    @Override
    public Feature descriptor() {
        return Feature.CT8C_REGEN;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        // Wave-2 (Task F) fills this. A scaffold registers nothing — zero-touch.
    }
}
