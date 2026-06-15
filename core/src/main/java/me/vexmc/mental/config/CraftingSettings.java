package me.vexmc.mental.config;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

/**
 * The disable-crafting module switch and its blocked-items set
 * (config.yml, {@code modules} map + {@code disable-crafting} section).
 *
 * <p>When enabled, any crafting result whose {@link Material} appears in
 * {@link #blocked()} is suppressed (the result slot is set to null), making
 * that item uncraftable.  The SHIELD is blocked by default — the primary
 * use-case for 1.7/1.8 play is to prevent players from equipping the off-hand
 * with the new 1.9 shield, complementing the disable-offhand module.</p>
 *
 * <p>Default OFF (era-exact no-op when disabled, per the zero-touch
 * invariant).</p>
 */
public record CraftingSettings(boolean enabled, @NotNull Set<Material> blocked) {

    /**
     * Default state: module OFF, but the blocked list already names SHIELD so
     * that enabling the module via {@code modules.disable-crafting: true} gives
     * the expected era behaviour without further configuration.
     */
    public static final CraftingSettings DEFAULTS =
            new CraftingSettings(false, Set.of(Material.SHIELD));

    /**
     * Parses the {@code disable-crafting} section from {@code reader}.
     *
     * <p>The {@code items} list is read with {@link ConfigReader#stringList};
     * each entry is matched case-insensitively via
     * {@link Material#matchMaterial(String)}.  Unknown names are skipped and
     * reported as config warnings so operators get actionable feedback at
     * reload time without the whole section failing.</p>
     *
     * @param enabled whether the module switch ({@code modules.disable-crafting}) is on
     * @param reader  validated reader for the {@code disable-crafting} section
     */
    public static @NotNull CraftingSettings parse(boolean enabled, @NotNull ConfigReader reader) {
        List<String> names = reader.stringList("items", List.of("SHIELD"));

        Set<Material> blocked = names.stream()
                .map(name -> {
                    Material mat = Material.matchMaterial(name);
                    if (mat == null) {
                        // Report the unrecognised name via the same issues mechanism
                        // the other parsers use, then skip it — one bad entry must
                        // not wipe out the rest of the list.
                        reader.issues().warn(
                                "disable-crafting.items",
                                "unknown material '" + name + "' — skipped",
                                "(none)");
                    }
                    return mat;
                })
                .filter(mat -> mat != null)
                .collect(Collectors.toUnmodifiableSet());

        return new CraftingSettings(enabled, blocked);
    }
}
