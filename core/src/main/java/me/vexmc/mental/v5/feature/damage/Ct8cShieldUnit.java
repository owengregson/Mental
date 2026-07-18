package me.vexmc.mental.v5.feature.damage;

import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;

/**
 * SKELETON — Combat Test 8c shields ({@code ct8c-shields}, design spec §2.6).
 * Wave-2 <b>Task E</b> fills this body (all through Bukkit damage events + the
 * Cooldowns API, zero-touch): the 148° arc override via {@code Ct8cShieldMath},
 * the hardcoded 5-damage cap with passthrough, instant + crouch-to-shield
 * blocking ({@code onGround && sneaking} + offhand shield), 0.5 knockback
 * resistance while blocking, an axe hit always disabling for {@code
 * Ct8cShieldMath.axeDisableTicks(cleavingLevel)}, and axe attack durability 1.
 * Banner shields are intentionally NOT implemented (spec §5).
 *
 * <p>Until then this is a genuine zero-touch no-op: the module defaults OFF, so
 * the reconciler never assembles it, and even assembled it registers nothing.</p>
 */
public final class Ct8cShieldUnit implements FeatureUnit {

    @Override
    public Feature descriptor() {
        return Feature.CT8C_SHIELDS;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        // Wave-2 (Task E) fills this. A scaffold registers nothing — zero-touch.
    }
}
