package me.vexmc.mental.v5.feature.sustain;

import me.vexmc.mental.platform.Recipes;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * The keyed half of the notch-apple recipe lifecycle, hoisted out of
 * {@link GoldenApplesUnit} so that class carries ZERO {@code NamespacedKey}-typed
 * symbols in its method/field descriptors. Bukkit's {@code registerEvents}
 * reflects over EVERY declared method of a listener class and swallows the
 * resulting {@code NoClassDefFoundError} into a single SEVERE line — killing
 * every handler in the class while the plugin keeps running (the 2.4.1 GAP-1
 * finding: {@code onConsume} never registered on 1.9.4–1.11.2 because a private
 * {@code nappleKey()} method returned {@code NamespacedKey}, absent below 1.12).
 *
 * <p>Deliberately NOT a {@link org.bukkit.event.Listener} and never handed to
 * {@code registerEvents}: nothing ever reflects over this class, so its
 * descriptors may name {@code NamespacedKey} freely. It is instantiated only
 * behind {@link Recipes#keyedRecipeCtor()} (1.12+, where the key type exists);
 * merely LOADING it — which resolving {@code GoldenApplesUnit}'s descriptors
 * does on every version — links nothing sub-floor, because descriptor types
 * resolve lazily at first use, not at class load.</p>
 */
final class NappleKeyed {

    private final NamespacedKey key;

    NappleKeyed(@NotNull Plugin plugin) {
        this.key = new NamespacedKey(plugin, "enchanted_golden_apple");
    }

    /** The keyed {@code ShapedRecipe} ctor (1.12+) — the modern registration form. */
    @NotNull ShapedRecipe shaped(@NotNull ItemStack result) {
        return new ShapedRecipe(key, result);
    }

    /** Keyed presence lookup — only callable where {@link Recipes#keyedRecipeLifecycle()} (1.16.5+). */
    boolean registered() {
        return Bukkit.getRecipe(key) != null;
    }

    /** Keyed removal — only callable where {@link Recipes#keyedRecipeLifecycle()} (1.16.5+). */
    void remove() {
        Bukkit.removeRecipe(key);
    }
}
