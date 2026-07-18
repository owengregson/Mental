package me.vexmc.mental.v5.config.settings;

import java.util.Set;

/**
 * The plugin-wide debug section (config.yml {@code debug}). The active category
 * keys are kept as raw strings rather than the retired {@code common.DebugCategory}
 * enum — the v5 debug sink (Phase 4) resolves them, so the framework stays
 * decoupled from the enum and forward-compatible with new categories. The YAML
 * surface ({@code debug.categories.<key>: true|false}) is unchanged.
 */
public record DebugSettings(boolean enabled, Set<String> categories) {

    public static final DebugSettings DEFAULTS = new DebugSettings(false, Set.of());
}
