package me.vexmc.mental.platform;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.jetbrains.annotations.NotNull;

/**
 * Cross-version keyed-recipe capability.
 *
 * <p>Three probes, three distinct floors. {@code org.bukkit.NamespacedKey} itself lands at 1.12 —
 * below it (1.9.4–1.11.2) only the deprecated non-keyed {@code ShapedRecipe(ItemStack)} ctor exists,
 * and no NamespacedKey-typed symbol may even appear in a <em>descriptor</em> of a class handed to
 * {@code registerEvents} (Bukkit reflects over every declared method and swallows the resulting
 * {@code NoClassDefFoundError} into one SEVERE line, silently killing every handler in the class —
 * the GAP-1 lesson). The keyed {@code ShapedRecipe(NamespacedKey, ItemStack)} ctor arrives with the
 * key type at 1.12; the key-addressable LIFECYCLE — both {@code Bukkit#getRecipe} and
 * {@code Bukkit#removeRecipe(NamespacedKey)} — completes only at 1.16.5 ({@code removeRecipe} 1.15,
 * {@code getRecipe} 1.16.5), so 1.12–1.15.2 registers keyed but must still manage removal through the
 * universal {@link Bukkit#recipeIterator()}.</p>
 *
 * <p>All three are resolved ONCE at class load, by NAME ({@link Class#forName} — never a
 * {@code NamespacedKey} class literal, which would itself be the linkage error being probed for).</p>
 */
public final class Recipes {

    private static final String NAMESPACED_KEY_CLASS = "org.bukkit.NamespacedKey";

    private static final boolean NAMESPACED_KEY = classPresent(NAMESPACED_KEY_CLASS);
    private static final boolean KEYED_CTOR = keyedShapedRecipeCtorPresent();
    private static final boolean KEYED_LIFECYCLE =
            methodPresent(Bukkit.class, "getRecipe", NAMESPACED_KEY_CLASS)
                    && methodPresent(Bukkit.class, "removeRecipe", NAMESPACED_KEY_CLASS);

    private Recipes() {}

    /** True when {@code org.bukkit.NamespacedKey} exists on this server (1.12+). */
    public static boolean namespacedKeyPresent() {
        return NAMESPACED_KEY;
    }

    /** True when the keyed {@code ShapedRecipe(NamespacedKey, ItemStack)} ctor exists (1.12+). */
    public static boolean keyedRecipeCtor() {
        return KEYED_CTOR;
    }

    /**
     * True when a recipe can be managed by key for its WHOLE lifecycle — both
     * {@code Bukkit#getRecipe} and {@code Bukkit#removeRecipe(NamespacedKey)} (1.16.5+). Below it,
     * removal scans {@link Bukkit#recipeIterator()} (present on every supported version).
     */
    public static boolean keyedRecipeLifecycle() {
        return KEYED_LIFECYCLE;
    }

    /** For the boot report: the era-truthful description of the keyed-recipe state. */
    public static @NotNull String describe() {
        if (KEYED_LIFECYCLE) {
            return "keyed recipe lifecycle (NamespacedKey get/remove, 1.16.5+)";
        }
        return KEYED_CTOR
                ? "keyed ctor only (get/remove by key land at 1.16.5 — lifecycle via recipeIterator)"
                : "no NamespacedKey (lands 1.12) — pre-keyed ctor, lifecycle via recipeIterator";
    }

    private static boolean keyedShapedRecipeCtorPresent() {
        try {
            ShapedRecipe.class.getConstructor(Class.forName(NAMESPACED_KEY_CLASS), ItemStack.class);
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError absent) {
            return false;
        }
    }

    private static boolean classPresent(@NotNull String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    /**
     * Whether {@code owner} declares {@code name(paramClassName)}. The parameter type is resolved by
     * NAME rather than a class literal, so probing a method whose parameter type is itself absent on
     * this server simply reports absent instead of failing class-init.
     */
    private static boolean methodPresent(
            @NotNull Class<?> owner, @NotNull String name, @NotNull String paramClassName) {
        try {
            owner.getMethod(name, Class.forName(paramClassName));
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError absent) {
            return false;
        }
    }
}
