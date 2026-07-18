package me.vexmc.mental.v5.feature.sustain;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.vexmc.mental.platform.PotionEffects;
import me.vexmc.mental.platform.Recipes;
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
 *
 * <p>Descriptor hygiene (the 2.4.1 GAP-1 rule): this class is handed to
 * {@code registerEvents}, so NO sub-floor Bukkit type — {@code NamespacedKey}
 * included — may appear in any of its method/field descriptors. The keyed-recipe
 * capability probes live in the platform {@link Recipes} resolver and every
 * NamespacedKey-typed symbol lives in the non-Listener {@link NappleKeyed}
 * helper; body-only references (the {@code getByKey} probe, guarded behind its
 * own boot resolution) are safe because constant-pool entries resolve lazily.</p>
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
     * absence — never a version parse. It selects the enchanted apple's REPRESENTATION: pre-flattening it
     * is {@code GOLDEN_APPLE} carrying data value 1 (detected by durability, the recipe result is the
     * {@code GOLDEN_APPLE:1} data-value stack); flattened it is its own material. This is independent of
     * the recipe LIFECYCLE probes below — 1.13.2/1.15.2 are flattened yet still lack the keyed
     * get/remove APIs.
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
     * The keyed recipe helper — built lazily via {@link #keyed()} only where the keyed ShapedRecipe ctor
     * exists ({@link Recipes#keyedRecipeCtor()}, 1.12+). Below that the field stays null and the recipe
     * uses the non-keyed ctor; wherever the keyed get/remove APIs are absent, removal falls back to an
     * iterator scan. NEVER a bare {@code NamespacedKey}: no sub-floor Bukkit type may appear in any
     * method/field descriptor of this class — Bukkit's {@code registerEvents} reflects over every declared
     * method and one {@code NoClassDefFoundError} kills EVERY handler here with a single swallowed SEVERE
     * line (the 2.4.1 GAP-1 finding: {@code onConsume} never registered on 1.9.4–1.11.2). The
     * NamespacedKey-typed symbols live in the non-Listener {@link NappleKeyed}, classloaded harmlessly and
     * linked only behind the probe.
     */
    private @Nullable NappleKeyed keyed;

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
        if (!Recipes.keyedRecipeLifecycle()) {
            plugin.getLogger().info("golden-apples: legacy recipe path — "
                    + (LEGACY_GAPPLE ? "the enchanted gapple is GOLDEN_APPLE:1 (data value); " : "")
                    + (Recipes.keyedRecipeCtor()
                            ? "recipe registered via the keyed ctor, "
                            : "recipe registered via the pre-keyed ctor (NamespacedKey lands 1.12), ")
                    + "managed via recipeIterator (Bukkit#getRecipe/removeRecipe by key land at "
                    + "1.16.5, absent on this version); era consume effects ACTIVE.");
        }
    }

    /* ------------------------------- recipe ------------------------------- */
    /*
     * The recipe lifecycle splits on TWO independent capabilities: the enchanted-gapple RESULT
     * representation (LEGACY_GAPPLE — data-value item vs its own material) and whether the key-addressable
     * get/remove APIs exist (Recipes.keyedRecipeLifecycle — 1.16.5+). Registration and the universal
     * recipeIterator work on every version, so the pre-1.16.5 band (1.9.4–1.15.2) is managed by scanning;
     * 1.16.5+ (every modern target) keeps the byte-identical keyed path.
     */

    private void registerNappleRecipe() {
        if (nappleRecipeRegistered()) {
            return; // already registered (rapid reload) — leave it
        }
        try {
            Bukkit.addRecipe(buildNappleRecipe());
        } catch (IllegalStateException duplicate) {
            // Another plugin (or a pre-crash enable) holds the key — harmless.
        }
    }

    private void removeNappleRecipe() {
        if (Recipes.keyedRecipeLifecycle()) {
            if (keyed != null) {
                keyed.remove();
            }
            return;
        }
        Iterator<Recipe> recipes = Bukkit.recipeIterator();
        while (recipes.hasNext()) {
            if (isOurNappleRecipe(recipes.next())) {
                recipes.remove();
            }
        }
    }

    /** Whether our notch-apple recipe is already registered — keyed lookup where possible, else a scan. */
    private boolean nappleRecipeRegistered() {
        if (Recipes.keyedRecipeLifecycle()) {
            return keyed().registered();
        }
        Iterator<Recipe> recipes = Bukkit.recipeIterator();
        while (recipes.hasNext()) {
            if (isOurNappleRecipe(recipes.next())) {
                return true;
            }
        }
        return false;
    }

    private ShapedRecipe buildNappleRecipe() {
        ShapedRecipe recipe = Recipes.keyedRecipeCtor()
                ? keyed().shaped(nappleResult())
                : legacyShapedRecipe(nappleResult());
        shapeNotchApple(recipe);
        return recipe;
    }

    /** The non-keyed ctor — the only one below 1.12 (deprecated on modern, unused there). */
    @SuppressWarnings("deprecation")
    private static ShapedRecipe legacyShapedRecipe(ItemStack result) {
        return new ShapedRecipe(result);
    }

    /** The notch-apple result: its own material where flattened, else the {@code GOLDEN_APPLE:1} data item. */
    @SuppressWarnings("deprecation") // the (Material,amount,data) ctor is the pre-1.13 enchanted-gapple form
    private ItemStack nappleResult() {
        return LEGACY_GAPPLE
                ? new ItemStack(Material.GOLDEN_APPLE, 1, (short) 1)
                : new ItemStack(ENCHANTED_GOLDEN_APPLE);
    }

    /** The keyed recipe helper, built lazily; only reachable where the keyed ctor exists (1.12+). */
    private NappleKeyed keyed() {
        if (keyed == null) {
            keyed = new NappleKeyed(plugin);
        }
        return keyed;
    }

    /** Whether a recipe is our notch apple, matched by result (ENCHANTED_GOLDEN_APPLE or GOLDEN_APPLE:1). */
    @SuppressWarnings("deprecation") // getDurability() reads the pre-1.13 data value distinguishing the gapples
    private boolean isOurNappleRecipe(Recipe recipe) {
        if (!(recipe instanceof ShapedRecipe shaped)) {
            return false;
        }
        ItemStack result = shaped.getResult();
        return LEGACY_GAPPLE
                ? result.getType() == Material.GOLDEN_APPLE && result.getDurability() == 1
                : result.getType() == ENCHANTED_GOLDEN_APPLE;
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
            // PotionEffects.of, not player.getPotionEffect(effectType): the single-effect accessor is absent
            // on 1.9.4 (floors at 1.10.2), where a direct call throws; the resolver scans the active set.
            PotionEffect existing = PotionEffects.of(player, effectType);
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
