package me.vexmc.mental.v5.feature.damage;

import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;

/**
 * Restores weapon durability loss on fast-path melee hits (the retired
 * {@code module.damage.ToolDurabilityModule} on the v5 seams). Like the retired
 * module, this unit owns no listener: the wear is applied inline by the fast path
 * ({@code HitRegistrationUnit} → {@link ToolWear}) after the authoritative
 * {@code victim.damage}, gated on this feature's live toggle — so all four
 * {@link Feature#facets()} surfaces are NONE (durability wear is neither a damage
 * amount nor a Bukkit rule). The unit's presence on the reconciler is what makes
 * {@code featureActive(TOOL_DURABILITY)} true; the fast path reads
 * {@code snapshot.enabled(TOOL_DURABILITY)} each hit.
 */
public final class ToolDurabilityUnit implements FeatureUnit {

    @Override
    public Feature descriptor() {
        return Feature.TOOL_DURABILITY;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        // No resource of its own — the fast path reads the live toggle and applies
        // the wear inline. The empty scope makes the feature reconciler-visible
        // (featureActive) without touching the game (zero-touch when disabled).
    }
}
