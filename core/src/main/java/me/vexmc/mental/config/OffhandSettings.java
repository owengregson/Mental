package me.vexmc.mental.config;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

/**
 * The disable-offhand module switch and its filtering configuration
 * (config.yml, {@code modules} map + {@code disable-offhand} section).
 *
 * <p>Era truth: the off-hand slot was added in 1.9.  On a 1.7/1.8 era server
 * the slot does not exist for players; this module blocks every path through
 * which an item can enter slot 40 on modern Paper.</p>
 *
 * <p>Filter semantics:
 * <ul>
 *   <li><b>whitelist mode</b> ({@code whitelist: true}) — only materials in
 *       {@code items} are permitted in the off-hand.  An empty whitelist
 *       blocks every item.</li>
 *   <li><b>blacklist mode</b> ({@code whitelist: false}) — materials in
 *       {@code items} are denied; everything else is allowed.  An empty
 *       blacklist allows every item.</li>
 * </ul>
 *
 * <p>Default OFF (era-exact no-op when disabled, per the zero-touch
 * invariant).  When enabled with the default empty whitelist, all off-hand
 * items are blocked — the maximally restrictive era default.</p>
 */
public record OffhandSettings(
        boolean enabled,
        boolean whitelist,
        @NotNull Set<Material> items,
        @NotNull String deniedMessage) {

    /**
     * Default state: module OFF, whitelist mode (all blocked when enabled),
     * empty item set, default denied message.
     *
     * <p>Enabling via {@code modules.disable-offhand: true} without further
     * configuration blocks the entire off-hand slot — the canonical era
     * behaviour.</p>
     */
    public static final OffhandSettings DEFAULTS =
            new OffhandSettings(false, true, Set.of(), "&cOff-hand is disabled");

    /**
     * Parses the {@code disable-offhand} section from {@code reader}.
     *
     * <p>The {@code items} list is read with {@link ConfigReader#stringList};
     * each entry is matched case-insensitively via
     * {@link Material#matchMaterial(String)}.  Unknown names are skipped and
     * reported as config warnings so operators get actionable feedback at
     * reload time without the whole section failing.</p>
     *
     * @param enabled whether the module switch ({@code modules.disable-offhand}) is on
     * @param reader  validated reader for the {@code disable-offhand} section
     */
    public static @NotNull OffhandSettings parse(boolean enabled, @NotNull ConfigReader reader) {
        boolean whitelist = reader.flag("whitelist", true);
        List<String> names = reader.stringList("items", List.of());

        Set<Material> items = names.stream()
                .map(name -> {
                    Material mat = Material.matchMaterial(name);
                    if (mat == null) {
                        // Report the unrecognised name via the same issues mechanism
                        // other parsers use, then skip it — one bad entry must not
                        // wipe out the rest of the list.
                        reader.issues().warn(
                                "disable-offhand.items",
                                "unknown material '" + name + "' — skipped",
                                "(none)");
                    }
                    return mat;
                })
                .filter(mat -> mat != null)
                .collect(Collectors.toUnmodifiableSet());

        String deniedMessage = reader.text("denied-message", "&cOff-hand is disabled");

        return new OffhandSettings(enabled, whitelist, items, deniedMessage);
    }
}
