package me.vexmc.mental.v5.feature.sustain;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.kernel.math.GoldenAppleEffects;
import me.vexmc.mental.kernel.math.GoldenAppleEffects.EffectSpec;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Restores 1.8.9 golden / notch apple behaviour (the retired
 * {@code module.consumable.GoldenAppleModule} on the v5 seam): the era effect
 * tables (kernel {@link GoldenAppleEffects}) and the notch-apple recipe (8 gold
 * BLOCKS + apple, removed in 1.9), both registered through the unit scope.
 *
 * <p>B13 terminal-event application: modern apples are component-food whose
 * vanilla effects land AFTER {@link PlayerItemConsumeEvent}, so the era table is
 * applied to the actual entity on a +1-tick {@code runOnLater} hop off the
 * confirmed consume — never by pre-mutating a hand item on a speculative interact.
 * The recipe is registered on enable and removed on scope close (zero-touch).</p>
 */
public final class GoldenApplesUnit implements FeatureUnit, Listener {

    /** lowercase Minecraft effect key → legacy Bukkit enum name for the getByName fallback. */
    private static final Map<String, String> BUKKIT_NAME_BY_KEY = Map.of(
            "regeneration", "REGENERATION",
            "absorption", "ABSORPTION",
            "resistance", "DAMAGE_RESISTANCE",
            "fire_resistance", "FIRE_RESISTANCE");

    /** {@code PotionEffectType.getByKey(NamespacedKey)} (Paper 1.20.5+); null on older builds. */
    private static final @Nullable Method GET_BY_KEY;

    /**
     * The enchanted-golden-apple material, resolved by NAME so the reference never hard-links to the
     * {@code Material.ENCHANTED_GOLDEN_APPLE} enum constant — that constant is absent below 1.13 (the
     * flattening), where a direct reference is a {@code NoSuchFieldError} at execution. {@code null}
     * pre-1.13, where the enchanted gapple is instead the data-value item {@code GOLDEN_APPLE:1} and the
     * recipe rides the pre-keyed recipe API (both handled by the legacy branch below).
     */
    private static final @Nullable Material ENCHANTED_GOLDEN_APPLE = Material.getMaterial("ENCHANTED_GOLDEN_APPLE");

    /**
     * True on a pre-flattening server (&lt; 1.13), resolved once by the enchanted-gapple constant's
     * absence — never a version parse. It selects the whole legacy strategy: the enchanted apple is
     * detected as {@code GOLDEN_APPLE} carrying data value 1 (rather than its own material), the
     * notch-apple recipe uses the non-keyed {@link ShapedRecipe#ShapedRecipe(ItemStack)} ctor (the keyed
     * ctor and {@code Bukkit#getRecipe}/{@code removeRecipe} are 1.13+), and the recipe result is the
     * {@code GOLDEN_APPLE:1} data-value stack.
     */
    private static final boolean LEGACY_GAPPLE = ENCHANTED_GOLDEN_APPLE == null;

    static {
        Method m = null;
        try {
            m = PotionEffectType.class.getMethod("getByKey", NamespacedKey.class);
        } catch (NoSuchMethodException | LinkageError ignored) {
            // Pre-1.20.5 Paper — getByName fallback. LinkageError also catches the NamespacedKey.class
            // literal being absent below 1.12 (the backport's oldest targets), so this static init is safe.
        }
        GET_BY_KEY = m;
    }

    private final Plugin plugin;
    private final Scheduling scheduling;

    /**
     * The notch-apple recipe key — built lazily inside the MODERN recipe branch only. {@code NamespacedKey}
     * is absent pre-1.12 and the keyed recipe API is 1.13+, so the legacy branch never touches this field
     * (it uses the non-keyed recipe API + an iterator scan for removal instead).
     */
    private @Nullable NamespacedKey nappleKey;

    public GoldenApplesUnit(@NotNull Plugin plugin, @NotNull Scheduling scheduling) {
        this.plugin = plugin;
        this.scheduling = scheduling;
    }

    @Override
    public Feature descriptor() {
        return Feature.GOLDEN_APPLES;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        scope.listen(this);
        scope.task(() -> {
            registerNappleRecipe();
            return this::removeNappleRecipe;
        });
        if (LEGACY_GAPPLE) {
            plugin.getLogger().info("golden-apples: pre-flattening server (< 1.13) — the enchanted golden "
                    + "apple is the data-value item GOLDEN_APPLE:1 and the notch-apple recipe uses the "
                    + "legacy non-keyed recipe API.");
        }
    }

    /* ------------------------------- recipe ------------------------------- */

    private void registerNappleRecipe() {
        if (LEGACY_GAPPLE) {
            registerLegacyNappleRecipe();
        } else {
            registerModernNappleRecipe();
        }
    }

    private void removeNappleRecipe() {
        if (LEGACY_GAPPLE) {
            removeLegacyNappleRecipe();
        } else if (nappleKey != null) {
            Bukkit.removeRecipe(nappleKey);
        }
    }

    /** 1.13+: a NamespacedKey-addressable recipe whose result is the ENCHANTED_GOLDEN_APPLE material. */
    private void registerModernNappleRecipe() {
        if (nappleKey == null) {
            nappleKey = new NamespacedKey(plugin, "enchanted_golden_apple");
        }
        if (Bukkit.getRecipe(nappleKey) != null) {
            return; // already registered (rapid reload) — leave it
        }
        try {
            ShapedRecipe recipe = new ShapedRecipe(nappleKey, new ItemStack(ENCHANTED_GOLDEN_APPLE));
            shapeNotchApple(recipe);
            Bukkit.addRecipe(recipe);
        } catch (IllegalStateException duplicate) {
            // Another plugin (or a pre-crash enable) holds the key — harmless.
        }
    }

    /**
     * Pre-1.13: the enchanted gapple is {@code GOLDEN_APPLE:1} and recipes are not key-addressable, so the
     * result is the data-value stack and the recipe uses the deprecated non-keyed ctor. There is no
     * {@code Bukkit#getRecipe}/{@code removeRecipe} by key here, so an iterator scan drops any stale copy
     * first — a rapid reload must not stack duplicate recipes.
     */
    @SuppressWarnings("deprecation") // non-keyed ShapedRecipe ctor + (Material,amount,data) stack are the era API
    private void registerLegacyNappleRecipe() {
        removeLegacyNappleRecipe();
        ShapedRecipe recipe = new ShapedRecipe(new ItemStack(Material.GOLDEN_APPLE, 1, (short) 1));
        shapeNotchApple(recipe);
        Bukkit.addRecipe(recipe);
    }

    /** Removes our pre-1.13 notch-apple recipe(s) by scanning for the GOLDEN_APPLE:1 shaped result. */
    @SuppressWarnings("deprecation") // getDurability() reads the pre-1.13 data value distinguishing the gapples
    private void removeLegacyNappleRecipe() {
        Iterator<Recipe> recipes = Bukkit.recipeIterator();
        while (recipes.hasNext()) {
            Recipe recipe = recipes.next();
            if (recipe instanceof ShapedRecipe shaped) {
                ItemStack result = shaped.getResult();
                if (result.getType() == Material.GOLDEN_APPLE && result.getDurability() == 1) {
                    recipes.remove();
                }
            }
        }
    }

    /** The era notch-apple shape: 8 gold BLOCKS around a single apple (removed from vanilla in 1.9). */
    private static void shapeNotchApple(ShapedRecipe recipe) {
        recipe.shape("ggg", "gag", "ggg");
        recipe.setIngredient('g', Material.GOLD_BLOCK);
        recipe.setIngredient('a', Material.APPLE);
    }

    /* ------------------------------- consume ------------------------------ */

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(@NotNull PlayerItemConsumeEvent event) {
        Boolean enchanted = classifyGapple(event.getItem());
        if (enchanted == null) {
            return; // not any golden apple
        }
        Player player = event.getPlayer();
        List<EffectSpec> specs = enchanted
                ? GoldenAppleEffects.notchApple()
                : GoldenAppleEffects.normalApple();
        // +1 tick: vanilla applies the modern component-food effects AFTER the
        // consume returns; the era table wins on the confirmed terminal (B13).
        scheduling.runOnLater(player, 1, () -> applyEra(player, specs), () -> {});
    }

    /**
     * Classifies a consumed item: {@code TRUE} = enchanted (notch) apple, {@code FALSE} = normal golden
     * apple, {@code null} = neither. Modern servers (1.13+) split the two into distinct materials;
     * pre-flattening they are the single material {@code GOLDEN_APPLE} distinguished by the item's data
     * value (1 = enchanted, 0 = normal).
     */
    @SuppressWarnings("deprecation") // getDurability() is the pre-1.13 data-value accessor
    private @Nullable Boolean classifyGapple(@NotNull ItemStack item) {
        Material type = item.getType();
        if (LEGACY_GAPPLE) {
            return type == Material.GOLDEN_APPLE ? item.getDurability() == 1 : null;
        }
        if (type == ENCHANTED_GOLDEN_APPLE) {
            return Boolean.TRUE;
        }
        return type == Material.GOLDEN_APPLE ? Boolean.FALSE : null;
    }

    /**
     * Applies the 1.8.9 era effects to the entity, era winning over the modern
     * vanilla effects (removed first), higher amplifier / longer duration winning
     * over anything the player already had. Runs on the player's owning region.
     */
    private void applyEra(@NotNull Player player, @NotNull List<EffectSpec> specs) {
        for (EffectSpec spec : specs) {
            PotionEffectType effectType = resolveType(spec.effectKey());
            if (effectType == null) {
                continue;
            }
            player.removePotionEffect(effectType); // wipe the modern vanilla effect first
            PotionEffect era = new PotionEffect(effectType, spec.durationTicks(), spec.amplifier());
            PotionEffect existing = player.getPotionEffect(effectType);
            if (existing == null) {
                player.addPotionEffect(era);
            } else if (era.getAmplifier() > existing.getAmplifier()
                    || (era.getAmplifier() == existing.getAmplifier()
                        && era.getDuration() > existing.getDuration())) {
                player.removePotionEffect(effectType);
                player.addPotionEffect(era);
            }
        }
    }

    @SuppressWarnings("deprecation") // getByName is the pre-1.20.5 spelling
    private @Nullable PotionEffectType resolveType(@NotNull String key) {
        if (GET_BY_KEY != null) {
            try {
                Object result = GET_BY_KEY.invoke(null, NamespacedKey.minecraft(key));
                if (result instanceof PotionEffectType type) {
                    return type;
                }
            } catch (ReflectiveOperationException ignored) {
                // Fall through to name-based lookup.
            }
        }
        String bukkitName = BUKKIT_NAME_BY_KEY.getOrDefault(key, key.toUpperCase(Locale.ROOT));
        return PotionEffectType.getByName(bukkitName);
    }
}
