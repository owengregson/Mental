package me.vexmc.mental.v5.config.settings;

import me.vexmc.mental.v5.config.ReelInPolicy;

/**
 * The fishing-knockback feature's tunables, ported field-for-field (minus the
 * module toggle) from the retired {@code config.FishingKnockbackSettings}. The
 * YAML surface is frozen; defaults are byte-identical.
 */
public record FishingKnockbackSettings(
        double damage,
        ReelInPolicy reelIn,
        boolean knockbackNonPlayerEntities) {

    public static final FishingKnockbackSettings DEFAULTS =
            new FishingKnockbackSettings(0.0001, ReelInPolicy.LEGACY, false);
}
