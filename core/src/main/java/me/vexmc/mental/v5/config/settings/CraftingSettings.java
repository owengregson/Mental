package me.vexmc.mental.v5.config.settings;

import java.util.Set;
import org.bukkit.Material;

/**
 * The disable-crafting feature's tunables, ported field-for-field (minus the
 * module toggle) from the retired {@code config.CraftingSettings}. The blocked
 * set names SHIELD by default so enabling the module gives the expected era
 * behavior with no further configuration. The YAML surface is frozen.
 */
public record CraftingSettings(Set<Material> blocked) {

    public static final CraftingSettings DEFAULTS = new CraftingSettings(Set.of(Material.SHIELD));
}
