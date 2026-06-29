package me.vexmc.mental.gui;

import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

/**
 * Name-resolved, cached menu materials.
 *
 * <p>A hard {@code Material.SOMETHING} reference is an enum constant fixed at
 * compile time: should a future year-scheme server rename or drop it, the menu
 * class would fail to link with a {@code NoSuchFieldError}. Resolving icons by
 * name through {@link Material#getMaterial(String)} instead lets an unknown
 * name degrade to a safe, always-present staple ({@link #FALLBACK}) — the menu
 * loses one glyph, never its whole screen. Every icon in the GUI flows through
 * here. Mirrors the {@code Attributes}/{@code Enchantments} resolve-by-name
 * idiom used elsewhere in the plugin.</p>
 */
public final class Materials {

    /** Present since the flattening; the universal fallback. */
    public static final Material FALLBACK = Material.STONE;

    private static final ConcurrentHashMap<String, Material> CACHE = new ConcurrentHashMap<>();

    private Materials() {}

    public static @NotNull Material of(@NotNull String name) {
        return CACHE.computeIfAbsent(name, key -> {
            Material material = Material.getMaterial(key);
            return material != null ? material : FALLBACK;
        });
    }
}
