package me.vexmc.mental.v5.config.settings;

import java.util.Set;
import org.bukkit.Material;

/**
 * The disable-offhand feature's tunables, ported field-for-field (minus the
 * module toggle) from the retired {@code config.OffhandSettings}. Whitelist
 * mode with an empty set is the maximally restrictive era default (all
 * off-hand items blocked when the module is enabled). The YAML surface is
 * frozen; defaults are byte-identical.
 */
public record OffhandSettings(
        boolean whitelist,
        Set<Material> items,
        String deniedMessage) {

    public static final OffhandSettings DEFAULTS =
            new OffhandSettings(true, Set.of(), "&cOff-hand is disabled");
}
