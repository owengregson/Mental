package me.vexmc.mental.v5.feature.cadence;

import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;

/**
 * Suppresses the 1.9 attack-result sounds (the retired
 * {@code module.rules.sound.AttackSoundModule} on the v5 seam) — a single netty
 * packet half registered through the unit's scope, so it dies with the feature
 * (no split-brain). Purely cosmetic (clientPresentation facet HANDLED, the others
 * NONE): combat plays no sound on swing, as in 1.7/1.8.
 */
public final class AttackSoundsUnit implements FeatureUnit {

    @Override
    public Feature descriptor() {
        return Feature.ATTACK_SOUNDS;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        scope.packets(new AttackSoundListener());
    }
}
