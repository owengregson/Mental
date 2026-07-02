package me.vexmc.mental.v5.feature.sustain;

import java.lang.reflect.Method;
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
     * pre-1.13, which gates the whole feature off there (the data-value gapple + legacy recipe path is
     * Phase 3; this phase only guarantees classload/boot safety).
     */
    private static final @Nullable Material ENCHANTED_GOLDEN_APPLE = Material.getMaterial("ENCHANTED_GOLDEN_APPLE");

    static {
        Method m = null;
        try {
            m = PotionEffectType.class.getMethod("getByKey", NamespacedKey.class);
        } catch (NoSuchMethodException ignored) {
            // Pre-1.20.5 Paper — getByName fallback.
        }
        GET_BY_KEY = m;
    }

    private final Plugin plugin;
    private final Scheduling scheduling;

    /** The notch-apple recipe key — built in {@link #assemble} (1.13+ only; NamespacedKey is absent pre-1.12). */
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
        if (ENCHANTED_GOLDEN_APPLE == null) {
            // Pre-1.13: the enchanted gapple is the data-value item GOLDEN_APPLE:1 and the notch-apple
            // recipe needs the legacy (non-keyed) ShapedRecipe ctor — both are Phase 3 correctness work.
            // Refuse to enable here with a loud line rather than register a broken recipe / crash.
            plugin.getLogger().warning("golden-apples: disabled — the enchanted golden apple is a "
                    + "pre-flattening data-value item on this server (< 1.13); era gapple support "
                    + "arrives in a later backport phase.");
            return;
        }
        this.nappleKey = new NamespacedKey(plugin, "enchanted_golden_apple");
        scope.listen(this);
        scope.task(() -> {
            registerNappleRecipe();
            return () -> Bukkit.removeRecipe(nappleKey);
        });
    }

    /* ------------------------------- recipe ------------------------------- */

    private void registerNappleRecipe() {
        if (Bukkit.getRecipe(nappleKey) != null) {
            return; // already registered (rapid reload) — leave it
        }
        try {
            ShapedRecipe recipe = new ShapedRecipe(nappleKey, new ItemStack(ENCHANTED_GOLDEN_APPLE));
            recipe.shape("ggg", "gag", "ggg");
            recipe.setIngredient('g', Material.GOLD_BLOCK);
            recipe.setIngredient('a', Material.APPLE);
            Bukkit.addRecipe(recipe);
        } catch (IllegalStateException duplicate) {
            // Another plugin (or a pre-crash enable) holds the key — harmless.
        }
    }

    /* ------------------------------- consume ------------------------------ */

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(@NotNull PlayerItemConsumeEvent event) {
        Material type = event.getItem().getType();
        if (type != Material.GOLDEN_APPLE && type != ENCHANTED_GOLDEN_APPLE) {
            return;
        }
        Player player = event.getPlayer();
        List<EffectSpec> specs = (type == ENCHANTED_GOLDEN_APPLE)
                ? GoldenAppleEffects.notchApple()
                : GoldenAppleEffects.normalApple();
        // +1 tick: vanilla applies the modern component-food effects AFTER the
        // consume returns; the era table wins on the confirmed terminal (B13).
        scheduling.runOnLater(player, 1, () -> applyEra(player, specs), () -> {});
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
