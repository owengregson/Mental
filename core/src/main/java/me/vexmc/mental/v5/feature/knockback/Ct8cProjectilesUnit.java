package me.vexmc.mental.v5.feature.knockback;

import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;

/**
 * SKELETON — Combat Test 8c projectiles ({@code ct8c-projectiles}, design spec
 * §2.10). Wave-2 <b>Task G</b> fills this body: snowball/egg 0-damage but full
 * 0.4 knock through the {@code DeliveryDesk} (extending the existing
 * {@code PROJECTILE_KNOCKBACK} submit path — this unit only supplies CT8c
 * policy), a 4-tick throw gate via the Cooldowns resolver, bow fatigue (draw
 * &gt; 3s ⇒ strip the crit-arrow flag + 0.25 spread), aim-direction-only
 * momentum, and Loyalty void-return (probing the modern rule first). Projectile
 * i-frames stay Task D's ({@code ct8c-iframes}).
 *
 * <p>Until then this is a genuine zero-touch no-op: the module defaults OFF, so
 * the reconciler never assembles it, and even assembled it registers nothing.</p>
 */
public final class Ct8cProjectilesUnit implements FeatureUnit {

    @Override
    public Feature descriptor() {
        return Feature.CT8C_PROJECTILES;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        // Wave-2 (Task G) fills this. A scaffold registers nothing — zero-touch.
    }
}
