package me.vexmc.mental.v5.feature.damage;

import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;

/**
 * SKELETON — Combat Test 8c invulnerability frames ({@code ct8c-iframes}, design
 * spec §2.4). Wave-2 <b>Task D</b> (the damage cluster) fills this body:
 * {@code min(Ct8cTables.attackDelayTicks(attackerSpeed), 10)} melee i-frames and
 * 0 for every projectile source — applied ONLY through the platform {@code
 * SpawnInvulnerability}-safe seam (NEVER a raw positive {@code setNoDamageTicks}
 * on 1.16.5–1.20.6, the total-invuln trap).
 *
 * <p>Until then this is a genuine zero-touch no-op: the module defaults OFF, so
 * the reconciler never assembles it, and even assembled it registers nothing.</p>
 */
public final class Ct8cIframesUnit implements FeatureUnit {

    @Override
    public Feature descriptor() {
        return Feature.CT8C_IFRAMES;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        // Wave-2 (Task D) fills this. A scaffold registers nothing — zero-touch.
    }
}
