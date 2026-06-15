package me.vexmc.mental.module.consumable;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.engine.CombatModule;
import me.vexmc.mental.module.consumable.GoldenAppleEffects.EffectSpec;
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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Restores 1.8.9 golden apple and enchanted golden apple (notch apple) behaviour:
 *
 * <ol>
 *   <li>Registers the notch-apple crafting recipe (8 gold BLOCKS + 1 apple →
 *       ENCHANTED_GOLDEN_APPLE) that was removed in 1.9.</li>
 *   <li>On consumption of either apple type, overrides the modern potion effects
 *       with the exact 1.8.9 era tables from {@link GoldenAppleEffects}.</li>
 * </ol>
 *
 * <p>Modern golden apples are component-food: vanilla applies effects AFTER
 * {@link PlayerItemConsumeEvent}.  We schedule a +1-tick deferred task via
 * {@link me.vexmc.mental.common.scheduling.Scheduling#runOnLater} so the era
 * effects are applied (and any modern effects are removed) on the tick AFTER
 * the consume event fires — the same pattern OCM uses with {@code runTaskLater}
 * but Folia-correct: the callback lands on the player's owning region thread
 * via {@code EntityScheduler.runDelayed}.</p>
 *
 * <p>Effect merge semantics: higher amplifier wins; equal amplifier with longer
 * duration wins; era effects always win over the vanilla modern effects (which
 * are removed first before the era table is applied).  This prevents the modern
 * absorption from "stacking" on top of the era absorption.</p>
 *
 * <p>PotionEffectType resolution is cross-version-stable: we try
 * {@code PotionEffectType.getByKey(NamespacedKey)} via reflection (available in
 * Paper 1.20.5+), and fall back to an explicit name-mapping table for the
 * 1.17.1–1.20.4 range where the old enum-field names must be used.  The notable
 * mapping is {@code "resistance"} → {@code DAMAGE_RESISTANCE} in pre-1.9 Bukkit
 * nomenclature (still present as an alias in modern Paper).</p>
 *
 * <p>Zero-touch: when disabled (the default), this module registers no listeners
 * and has no effect on the game.</p>
 */
public final class GoldenAppleModule extends CombatModule implements Listener {

    /**
     * Maps lowercase Minecraft effect key → old Bukkit name used by
     * {@link PotionEffectType#getByName(String)} on 1.17.1–1.20.4 Paper.
     * Modern Paper still accepts these old names as aliases, so this map is
     * safe on the full 1.17.1–26.x range.
     *
     * <p>The critical entry is {@code "resistance"} which was named
     * {@code DAMAGE_RESISTANCE} in the original Bukkit API.</p>
     */
    private static final Map<String, String> BUKKIT_NAME_BY_KEY = Map.of(
            "regeneration",   "REGENERATION",
            "absorption",     "ABSORPTION",
            "resistance",     "DAMAGE_RESISTANCE",
            "fire_resistance","FIRE_RESISTANCE"
    );

    /**
     * Reflected reference to {@code PotionEffectType.getByKey(NamespacedKey)},
     * available on Paper 1.20.5+. {@code null} on older builds where we fall
     * back to {@link #BUKKIT_NAME_BY_KEY} + {@code getByName}.
     */
    private static final @Nullable Method GET_BY_KEY;

    static {
        Method m = null;
        try {
            m = PotionEffectType.class.getMethod("getByKey", NamespacedKey.class);
        } catch (NoSuchMethodException ignored) {
            // Pre-1.20.5 Paper — will use getByName fallback.
        }
        GET_BY_KEY = m;
    }

    /**
     * NamespacedKey for the notch-apple recipe — stored so we can remove it
     * on disable and guard against duplicate registration on reload.
     */
    private final NamespacedKey nappleKey;

    public GoldenAppleModule(@NotNull MentalServices services) {
        super(services,
                "old-golden-apples",
                "Old Golden Apples",
                "Restores 1.8.9 golden/notch apple effects and re-registers the notch-apple "
                        + "crafting recipe (8 gold blocks + apple, removed in 1.9).",
                DebugCategory.CONFIG);
        this.nappleKey = new NamespacedKey(services.plugin(), "enchanted_golden_apple");
    }

    @Override
    public boolean configEnabled() {
        return services.config().goldenApple().enabled();
    }

    @Override
    protected void onEnable() throws Exception {
        listen(this);
        registerNappleRecipe();
    }

    @Override
    protected void onDisable() {
        // Remove the notch-apple recipe so the crafting table returns to modern
        // behaviour when the module is toggled off (zero-touch invariant).
        Bukkit.removeRecipe(nappleKey);
        debug.log(() -> "removed notch-apple recipe (" + nappleKey + ")");
    }

    @Override
    protected void onReload() throws Exception {
        // Recipe is registered by onEnable; reload does not need to re-register
        // (the module stays active across reloads).  Nothing else to do.
    }

    /* ------------------------------------------------------------------ */
    /*  Recipe registration                                                */
    /* ------------------------------------------------------------------ */

    private void registerNappleRecipe() {
        // Guard against duplicate registration on rapid reload cycles.
        // Bukkit.addRecipe throws IllegalStateException if the key is taken.
        if (Bukkit.getRecipe(nappleKey) != null) {
            debug.log(() -> "notch-apple recipe already registered — skipping");
            return;
        }

        try {
            // Era recipe: 8 gold BLOCKS surrounding 1 apple → ENCHANTED_GOLDEN_APPLE.
            // The normal gapple recipe (8 gold INGOTS) is still valid in modern MC
            // and is NOT touched here.  [abt.java:137]
            ShapedRecipe recipe = new ShapedRecipe(nappleKey, new ItemStack(Material.ENCHANTED_GOLDEN_APPLE));
            recipe.shape("ggg", "gag", "ggg");
            recipe.setIngredient('g', Material.GOLD_BLOCK);
            recipe.setIngredient('a', Material.APPLE);
            Bukkit.addRecipe(recipe);
            debug.log(() -> "registered notch-apple recipe (" + nappleKey + ")");
        } catch (IllegalStateException duplicate) {
            // Another plugin (or a previous enable before a crash) already
            // registered this key — this is harmless; log and continue.
            debug.log(() -> "notch-apple recipe already present (IllegalStateException) — skipping");
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Consume event                                                      */
    /* ------------------------------------------------------------------ */

    /**
     * Intercepts golden apple consumption and schedules the era-effect override.
     *
     * <p>Modern golden apples are component-food: the vanilla code applies effects
     * AFTER this event returns, so the override must run +1 tick later to win.
     * The task is dispatched via {@link me.vexmc.mental.common.scheduling.Scheduling#runOnLater}
     * which uses {@code EntityScheduler.runDelayed} on Folia (region-correct) and
     * {@code BukkitScheduler.runTaskLater} on Paper (main-thread, same semantics).</p>
     *
     * <p>Folia safety note: this event fires on the player's owning region thread;
     * the +1 tick {@code runOnLater} callback lands back on the same region thread.</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(@NotNull PlayerItemConsumeEvent event) {
        Material type = event.getItem().getType();
        if (type != Material.GOLDEN_APPLE && type != Material.ENCHANTED_GOLDEN_APPLE) {
            return;
        }

        Player player = event.getPlayer();
        List<EffectSpec> specs = (type == Material.ENCHANTED_GOLDEN_APPLE)
                ? GoldenAppleEffects.notchApple()
                : GoldenAppleEffects.normalApple();

        // Capture current active effects BEFORE the consume completes so we
        // know what the player had independently of the apple.
        Collection<PotionEffect> priorEffects = player.getActivePotionEffects();

        // +1 tick: vanilla applies the modern effects on the SAME tick as the
        // consume (after event handlers return); we override them one tick later.
        services.scheduling().runOnLater(player, 1, () -> applyEra(player, specs, priorEffects), () -> {
            // Player left or became invalid before the task ran — nothing to do.
        });

        debug.log(() -> "queued era effects (+1 t) for " + player.getName()
                + " consuming " + type.name());
    }

    /* ------------------------------------------------------------------ */
    /*  Era-effect application (runs on the player's region thread)        */
    /* ------------------------------------------------------------------ */

    /**
     * Removes the vanilla modern effects for the apple type and applies the
     * 1.8.9 era effects, merging against whatever the player already had.
     *
     * <p><b>Must be called on the player's owning region thread.</b> All callers
     * reach this method through {@link me.vexmc.mental.common.scheduling.Scheduling#runOnLater}.</p>
     *
     * <p>Merge rule (mirrors OCM {@code applyEffects} semantics):
     * <ul>
     *   <li>Higher amplifier always wins.</li>
     *   <li>Equal amplifier with longer remaining duration wins.</li>
     *   <li>Era values always override the modern vanilla effects (those are
     *       removed unconditionally before applying the era table).</li>
     * </ul>
     *
     * @param player       the consumer
     * @param specs        era effect table to apply
     * @param priorEffects effects the player had BEFORE eating (for merge reference)
     */
    private void applyEra(
            @NotNull Player player,
            @NotNull List<EffectSpec> specs,
            @NotNull Collection<PotionEffect> priorEffects) {

        // Remove every effect in the era table so the modern vanilla effects (just
        // applied) are wiped clean; this prevents the modern Absorption from
        // running concurrently with the era Absorption at a different amplitude.
        for (EffectSpec spec : specs) {
            PotionEffectType type = resolveType(spec.effectKey());
            if (type != null) {
                player.removePotionEffect(type);
            }
        }

        // Restore what the player had BEFORE eating (removes any vanilla-applied
        // effects we stripped above that the player already had — they will be
        // reapplied below with the winning merge value).
        player.addPotionEffects(priorEffects);

        // Apply era effects: higher-amplifier wins; equal-amplifier longer-wins.
        for (EffectSpec spec : specs) {
            PotionEffectType type = resolveType(spec.effectKey());
            if (type == null) {
                debug.log(() -> "skipping unknown potion effect key: " + spec.effectKey());
                continue;
            }

            PotionEffect era = new PotionEffect(type, spec.durationTicks(), spec.amplifier());

            // Find any existing effect of the same type.
            PotionEffect existing = player.getPotionEffect(type);
            if (existing == null) {
                player.addPotionEffect(era);
            } else if (era.getAmplifier() > existing.getAmplifier()) {
                // Era is stronger — override.
                player.removePotionEffect(type);
                player.addPotionEffect(era);
            } else if (era.getAmplifier() == existing.getAmplifier()
                    && era.getDuration() > existing.getDuration()) {
                // Same strength but era lasts longer — refresh.
                player.removePotionEffect(type);
                player.addPotionEffect(era);
            }
            // else existing is stronger or longer — do nothing.
        }

        debug.log(() -> "applied era apple effects to " + player.getName());
    }

    /* ------------------------------------------------------------------ */
    /*  PotionEffectType resolution — cross-version-stable                 */
    /* ------------------------------------------------------------------ */

    /**
     * Resolves a {@link PotionEffectType} by its lowercase Minecraft namespace
     * path (e.g. {@code "regeneration"}, {@code "resistance"}).
     *
     * <p>Resolution strategy (cross-version, 1.17.1–26.x):</p>
     * <ol>
     *   <li>If {@code PotionEffectType.getByKey(NamespacedKey)} is available at
     *       runtime (Paper 1.20.5+), invoke it via reflection.  This is the modern
     *       path and is registry-backed.</li>
     *   <li>Otherwise use {@link #BUKKIT_NAME_BY_KEY} to map the Minecraft key to
     *       the old Bukkit enum name and call {@code PotionEffectType.getByName}.
     *       The key difference: {@code "resistance"} maps to {@code DAMAGE_RESISTANCE}
     *       in pre-1.9 Bukkit nomenclature.  Modern Paper still accepts the old
     *       names, so the fallback is safe across the full range.</li>
     * </ol>
     *
     * @param key lowercase Minecraft path, e.g. {@code "regeneration"}
     * @return the resolved type, or {@code null} if unresolvable (caller skips)
     */
    @SuppressWarnings("deprecation")
    private @Nullable PotionEffectType resolveType(@NotNull String key) {
        // Primary: getByKey via reflection (Paper 1.20.5+).
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

        // Fallback: map Minecraft key → Bukkit name and use getByName.
        // getByName is available in all Paper versions from 1.17.1 through 26.x.
        String bukkitName = BUKKIT_NAME_BY_KEY.get(key);
        if (bukkitName == null) {
            // Key not in our table — try the key itself uppercased as a last resort.
            bukkitName = key.toUpperCase(java.util.Locale.ROOT);
        }
        return PotionEffectType.getByName(bukkitName);
    }
}
