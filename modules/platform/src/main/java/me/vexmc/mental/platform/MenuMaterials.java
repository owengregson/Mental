package me.vexmc.mental.platform;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Name-resolved, cached menu-icon materials — the platform layer's resolve-by-name
 * idiom applied to {@link Material}.
 *
 * <p>A hard {@code Material.SOMETHING} reference is an enum constant fixed at
 * compile time: should a future year-scheme server rename or drop it, the class
 * would fail to link with a {@code NoSuchFieldError}. Resolving icons by name
 * through {@link Material#getMaterial(String)} instead lets an unknown name
 * degrade to a safe, always-present staple ({@link #FALLBACK}) — a menu loses one
 * glyph, never its whole screen, and the resolution never throws. This is the same
 * name-probe technique {@link Attributes} and {@link Enchantments} use for their
 * cross-version constants; it lives here in the platform layer so every icon in
 * the GUI flows through one cross-version-safe resolver rather than a raw
 * {@code Material.valueOf} on the render path.</p>
 *
 * <p>Lifted verbatim (behaviour-for-behaviour) from the retired
 * {@code gui/Materials} reuse-ledger asset; only its home moved from the GUI
 * package to the platform layer, and the class was renamed to disambiguate it
 * from the platform's other material contract ({@link EffectiveMaterial}).</p>
 */
public final class MenuMaterials {

    /** Present since the flattening; the universal fallback. */
    public static final Material FALLBACK = Material.STONE;

    /**
     * Modern icon name → its pre-flattening (≤ 1.12) enum-constant spelling, for the
     * GUI icon names that were renamed by the flattening. Without this, a pre-1.13
     * server misses the modern name and every affected icon degrades to {@link
     * #FALLBACK} — a menu of grey stone rather than its intended glyphs. Enumerated
     * from exactly the names the GUI requests through {@link #of} and verified
     * present on the real 1.9.4 / 1.12.2 {@code Material} enum (the fallback stays a
     * last resort, never the strategy). A modern server resolves the modern name
     * directly and never consults this table.
     */
    private static final Map<String, String> LEGACY_ALIAS = Map.ofEntries(
            Map.entry("IRON_BARS", "IRON_FENCE"),
            Map.entry("LIME_DYE", "INK_SACK"),
            Map.entry("REPEATER", "DIODE"),
            Map.entry("PISTON", "PISTON_BASE"),
            Map.entry("CLOCK", "WATCH"),
            Map.entry("SNOWBALL", "SNOW_BALL"),
            Map.entry("OAK_SIGN", "SIGN"),
            Map.entry("WRITABLE_BOOK", "BOOK_AND_QUILL"),
            Map.entry("REDSTONE_TORCH", "REDSTONE_TORCH_ON"),
            Map.entry("GOLDEN_SWORD", "GOLD_SWORD"),
            Map.entry("ENDER_EYE", "EYE_OF_ENDER"),
            Map.entry("GRAY_STAINED_GLASS_PANE", "STAINED_GLASS_PANE"),
            Map.entry("COMPARATOR", "REDSTONE_COMPARATOR"),
            Map.entry("CRAFTING_TABLE", "WORKBENCH"),
            Map.entry("ENCHANTED_GOLDEN_APPLE", "GOLDEN_APPLE"),
            Map.entry("FIREWORK_ROCKET", "FIREWORK"),
            Map.entry("GLISTERING_MELON_SLICE", "SPECKLED_MELON"),
            Map.entry("PLAYER_HEAD", "SKULL_ITEM"),
            Map.entry("NETHERITE_SWORD", "DIAMOND_SWORD"),
            Map.entry("NETHERITE_AXE", "DIAMOND_AXE"),
            Map.entry("TRIDENT", "GOLD_SWORD"),
            Map.entry("TARGET", "SLIME_BALL"));

    private static final ConcurrentHashMap<String, Material> CACHE = new ConcurrentHashMap<>();

    private MenuMaterials() {}

    public static @NotNull Material of(@NotNull String name) {
        return CACHE.computeIfAbsent(name, key -> {
            Material material = Material.getMaterial(key);
            if (material != null) {
                return material; // the modern name resolves — the whole-range common case
            }
            String legacy = LEGACY_ALIAS.get(key);
            if (legacy != null) {
                Material aliased = Material.getMaterial(legacy);
                if (aliased != null) {
                    return aliased; // pre-flattening server — render the renamed glyph, not stone
                }
            }
            return FALLBACK;
        });
    }

    /**
     * A stained-glass background pane of the given colour, era-correct: the
     * per-colour material on 1.13+, or STAINED_GLASS_PANE with the colour's data
     * value below (the deprecated data ctor is the ONLY way to colour a pane
     * there; it is verified present on the 1.17.1 compile floor and the legacy
     * branch can never execute on a modern server because the per-colour name
     * resolves first — data is never passed on the modern path). Returns a fresh
     * stack each call so callers may attach meta without aliasing.
     */
    @SuppressWarnings("deprecation")
    public static @NotNull ItemStack pane(@NotNull PaneColor color) {
        Material modern = Material.getMaterial(color.modernName());
        if (modern != null) {
            return new ItemStack(modern);            // modern path: no data value, ever
        }
        Material legacy = Material.getMaterial("STAINED_GLASS_PANE");
        if (legacy != null) {
            return new ItemStack(legacy, 1, color.legacyData());  // pre-1.13 colour-by-data
        }
        return new ItemStack(FALLBACK);              // unreachable in the range; boot test screams
    }

    /** The pre-flattening spelling for a renamed icon name, or {@code null} — test seam. */
    static @Nullable String legacyAlias(@NotNull String modernName) {
        return LEGACY_ALIAS.get(modernName);
    }
}
