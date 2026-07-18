package me.vexmc.mental.v5.feature.damage;

import org.bukkit.Bukkit;
import me.vexmc.mental.platform.CleavingRegistrar;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;

/**
 * The Combat Test 8c Cleaving enchantment (spec §2.9). Cleaving is axe-only, max
 * level 3, and does two things — both consumed elsewhere, so this unit owns no
 * listener of its own: its damage bonus ({@code 1+level}) folds through {@code
 * Ct8cDamageUnit}'s composition, and its shield-disable scaling
 * ({@code +10 ticks/level}) is consumed by {@code Ct8cShieldUnit}. Both read the
 * weapon's level through the platform {@link CleavingRegistrar} handle.
 *
 * <p>All this unit does at enable is trigger the one-time registry injection
 * (contained entirely in the platform seam) and publish the resolved handle. The
 * injection is idempotent and cached, so a reload re-enable reuses it; it happens
 * ONLY when the feature is enabled, so a disabled Cleaving injects nothing —
 * zero-touch. Below the registry floor (or on any injection failure) the seam
 * degrades loud and both consumers fold Cleaving in as level {@code 0} (the
 * documented spec §5 gap). Registries cannot be unfrozen-and-pruned, so a
 * later disable leaves the (now unused) enchant registered — a documented,
 * behaviourally-inert residue.</p>
 */
public final class CleavingUnit implements FeatureUnit {

    @Override
    public Feature descriptor() {
        return Feature.CLEAVING;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        // The registry write lives in the platform seam; the loud degrade line
        // routes to the server logger (this no-arg unit carries no injected
        // plugin logger). Idempotent — install() caches after the first boot.
        CleavingRegistrar.install(message -> Bukkit.getLogger().warning("[Mental] " + message));
    }
}
