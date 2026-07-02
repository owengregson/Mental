package me.vexmc.mental.platform;

import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

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

    private static final ConcurrentHashMap<String, Material> CACHE = new ConcurrentHashMap<>();

    private MenuMaterials() {}

    public static @NotNull Material of(@NotNull String name) {
        return CACHE.computeIfAbsent(name, key -> {
            Material material = Material.getMaterial(key);
            return material != null ? material : FALLBACK;
        });
    }
}
