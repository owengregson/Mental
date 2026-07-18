package me.vexmc.mental.v5.feature.damage;

import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;

/**
 * Restores the pre-1.9 Strength/Weakness damage values on fast-path melee (the
 * retired {@code module.potion} values half on the v5 seams). Like tool
 * durability, the era value ({@code ×3.5} Strength I, {@code −2·(amp+1)}
 * Weakness, applied to the pure weapon base before crit) is composed inline by
 * the fast path's {@link DamageShaper}, gated on this feature's live toggle — so
 * this unit owns no listener; its reconciler presence is what makes
 * {@code featureActive(POTION_VALUES)} true (fastPathDamage facet HANDLED, the
 * others NONE — mob/vanilla hits keep vanilla values by scope).
 */
public final class PotionValuesUnit implements FeatureUnit {

    @Override
    public Feature descriptor() {
        return Feature.POTION_VALUES;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        // No resource of its own — the fast path's shaper reads the live toggle
        // and composes the era Strength/Weakness inline (zero-touch when disabled).
    }
}
