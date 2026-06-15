package me.vexmc.mental.module.potion;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.engine.CombatModule;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Restores the pre-1.9 (1.8) potion DURATIONS.
 *
 * <p>1.9 shortened many potion durations. When a player drinks, throws, or
 * dispenses a potion, this module rewrites the potion's {@link PotionMeta} so its
 * single effect runs for the 1.8 length from {@link PotionDurations} (OCM's
 * {@code old-potion-effects.potion-durations} table, in ticks). This mirrors the
 * three hooks OCM's {@code ModuleOldPotionEffects} uses and the golden-apple
 * item-rewrite shape (Task 6).</p>
 *
 * <p><b>Scope:</b> the DURATION half only. The Strength/Weakness damage
 * <em>values</em> are a separate concern and are NOT touched here — the rewritten
 * effect keeps the vanilla amplifier (level I = amp 0, strong = amp 1).</p>
 *
 * <h2>Events</h2>
 * <ul>
 *   <li><b>Drink</b> — {@link PlayerItemConsumeEvent}: rewrite the item meta and
 *       set it back on the event so the consumed effect uses the era duration.</li>
 *   <li><b>Splash / lingering throw</b> — {@link PlayerInteractEvent} RIGHT_CLICK:
 *       the potion is rewritten in-hand the instant before it is thrown.</li>
 *   <li><b>Dispense</b> — {@link BlockDispenseEvent}: the dispensed splash/
 *       lingering item is rewritten before launch.</li>
 * </ul>
 *
 * <h2>Timing</h2>
 * <p>OCM rewrites the potion item's meta on these events; the server then reads
 * the rewritten meta to apply the effect (drink) or build the thrown entity
 * (splash/dispense). We follow the same in-place rewrite — no deferral is needed
 * (and no scheduler is used), because the effect is read FROM the item we edit,
 * not applied independently the way component-food golden apples are.</p>
 *
 * <h2>Cross-version PotionMeta</h2>
 * <p>Mental's {@code core} compiles against the 1.17.1 floor API, where
 * {@code PotionMeta.getBasePotionType()}/{@code setBasePotionType(...)} do not
 * exist as methods at all — only the (now-deprecated) {@code getBasePotionData()}
 * /{@code setBasePotionData(...)} do. So the modern {@code PotionType} accessors
 * are invoked <b>reflectively</b> (resolved once at class load) and PREFERRED
 * when present (Paper 1.20.5+), falling back to the directly-compiled
 * {@code PotionData} methods; that fallback is itself guarded with a
 * {@link NoSuchMethodError} catch in case a future runtime removes the
 * deprecated {@code PotionData} API entirely. The extended/upgraded flags come
 * from the folded {@code PotionType} enum name on modern servers (e.g.
 * {@code STRONG_STRENGTH}) and from {@code PotionData.isExtended()}/
 * {@code isUpgraded()} on older ones. The effect type is resolved by namespaced
 * key via {@code PotionEffectType.getByKey} reflection (Paper 1.20.5+) with a
 * {@code getByName} fallback — the same defensive shape as
 * {@code GoldenAppleModule}.</p>
 *
 * <h2>Folia</h2>
 * <p>All three events fire on the owning region thread (the item is in-hand or
 * being dispensed in that region), so the item-meta edits are safe inline.</p>
 *
 * <h2>Zero-touch</h2>
 * <p>When disabled (the default), this module registers no listeners and leaves
 * modern potion durations completely unchanged.</p>
 */
public final class PotionDurationModule extends CombatModule implements Listener {

    /**
     * Maps a base potion-type token (the value the duration table is keyed by) to
     * the lowercase Minecraft effect key used to resolve a {@link PotionEffectType}.
     * For these single-effect potions the base name equals the effect key except
     * for the 1.9 renames (swiftness→speed, leaping→jump_boost, slowness→slowness,
     * strength→strength) — modern Paper still accepts the aliases via getByName, so
     * we list the canonical Minecraft effect keys here for getByKey.
     */
    private static final Map<String, String> EFFECT_KEY_BY_BASE = Map.ofEntries(
            Map.entry("regeneration", "regeneration"),
            Map.entry("swiftness", "speed"),
            Map.entry("fire_resistance", "fire_resistance"),
            Map.entry("poison", "poison"),
            Map.entry("night_vision", "night_vision"),
            Map.entry("weakness", "weakness"),
            Map.entry("strength", "strength"),
            Map.entry("slowness", "slowness"),
            Map.entry("leaping", "jump_boost"),
            Map.entry("water_breathing", "water_breathing"),
            Map.entry("invisibility", "invisibility"));

    /** Modern→canonical base aliases so any version's PotionType name resolves. */
    private static final Map<String, String> BASE_ALIASES = Map.of(
            "speed", "swiftness",
            "jump", "leaping",
            "slow", "slowness");

    /**
     * Reflected {@code PotionEffectType.getByKey(NamespacedKey)} (Paper 1.20.5+);
     * {@code null} on older builds where {@code getByName} is the fallback.
     */
    private static final @Nullable Method GET_BY_KEY;

    /**
     * Reflected modern PotionMeta base-type accessors (Paper 1.20.5+). These do
     * not exist on the 1.17.1 floor API Mental compiles against, so they must be
     * reflected rather than called directly. {@code null} when absent at runtime,
     * where the directly-compiled {@code PotionData} methods are the fallback.
     */
    private static final @Nullable Method GET_BASE_POTION_TYPE;
    private static final @Nullable Method SET_BASE_POTION_TYPE;

    static {
        Method getByKey = null;
        try {
            getByKey = PotionEffectType.class.getMethod("getByKey", NamespacedKey.class);
        } catch (NoSuchMethodException ignored) {
            // Pre-1.20.5 Paper — getByName fallback.
        }
        GET_BY_KEY = getByKey;

        Method getType = null;
        Method setType = null;
        try {
            getType = PotionMeta.class.getMethod("getBasePotionType");
            setType = PotionMeta.class.getMethod("setBasePotionType", PotionType.class);
        } catch (NoSuchMethodException ignored) {
            // Pre-1.20.5 Paper — PotionData fallback.
        }
        GET_BASE_POTION_TYPE = getType;
        SET_BASE_POTION_TYPE = setType;
    }

    public PotionDurationModule(@NotNull MentalServices services) {
        super(services,
                "old-potion-durations",
                "Old Potion Durations",
                "Restores the pre-1.9 (1.8) potion durations on drink, splash/lingering "
                        + "throw, and dispense (OCM-sourced duration table).",
                DebugCategory.CONFIG);
    }

    @Override
    public boolean configEnabled() {
        return services.config().potionDuration().enabled();
    }

    @Override
    protected void onEnable() {
        listen(this);
    }

    @Override
    protected void onDisable() {
        // Listener auto-unhooked by CombatModule; nothing else to undo (we never
        // mutate persistent state — only items as they are used). Zero-touch.
    }

    /* ------------------------------------------------------------------ */
    /*  Drink                                                              */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrink(@NotNull PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.POTION) {
            return;
        }
        if (adjustPotion(item, false)) {
            // Re-set so the consume path reads the rewritten meta (OCM does this).
            event.setItem(item);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Splash / lingering throw                                           */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onThrow(@NotNull PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (!isThrowable(item)) {
            return;
        }
        // Rewrite in-hand the instant before the throw; the projectile reads it.
        adjustPotion(item, true);
    }

    /* ------------------------------------------------------------------ */
    /*  Dispense                                                           */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(@NotNull BlockDispenseEvent event) {
        ItemStack item = event.getItem();
        if (!isThrowable(item)) {
            return;
        }
        if (adjustPotion(item, true)) {
            event.setItem(item);
        }
    }

    private static boolean isThrowable(@Nullable ItemStack item) {
        if (item == null) {
            return false;
        }
        Material type = item.getType();
        return type == Material.SPLASH_POTION || type == Material.LINGERING_POTION;
    }

    /* ------------------------------------------------------------------ */
    /*  Item rewrite                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * Rewrites {@code item}'s {@link PotionMeta} to the era duration if it is a
     * potion with an era-overridable type. Instant potions and unknown / no-effect
     * bases are left untouched (the table returns {@link PotionDurations#NO_OVERRIDE}).
     *
     * <p>Mirrors OCM's {@code adjustPotion}: add a custom effect at the era
     * duration, then reset the base to a no-effect {@code WATER} type so the base
     * type's own (modern, shortened) effect does not also apply.</p>
     *
     * @param item   the potion item (edited in place)
     * @param splash {@code true} for splash/lingering, {@code false} for drink
     * @return {@code true} if the meta was rewritten, {@code false} otherwise
     */
    private boolean adjustPotion(@NotNull ItemStack item, boolean splash) {
        if (!(item.getItemMeta() instanceof PotionMeta meta)) {
            return false;
        }

        BaseType base = readBaseType(meta);
        if (base == null) {
            return false;
        }

        int eraTicks = PotionDurations.eraDurationTicks(base.name(), base.extended(), base.upgraded(), splash);
        if (eraTicks == PotionDurations.NO_OVERRIDE) {
            debug.log(() -> "leaving potion '" + base.name() + "' untouched (no era override)");
            return false;
        }

        PotionEffectType effectType = resolveEffectType(base.name());
        if (effectType == null) {
            debug.log(() -> "could not resolve effect type for potion '" + base.name() + "'");
            return false;
        }

        // Vanilla amplifier: level I = 0, strong (upgraded) = 1. We restore the
        // 1.8 DURATION only — the damage VALUES of Strength/Weakness are owned by
        // a separate task, so the amplifier is left at the vanilla level here.
        int amplifier = base.upgraded() ? 1 : 0;

        meta.addCustomEffect(new PotionEffect(effectType, eraTicks, amplifier), true);
        clearBaseType(meta);
        item.setItemMeta(meta);

        debug.log(() -> "rewrote potion '" + base.name() + "' to " + eraTicks
                + " t (amp " + amplifier + ", splash=" + splash + ")");
        return true;
    }

    /* ------------------------------------------------------------------ */
    /*  Cross-version base-type read / clear                              */
    /* ------------------------------------------------------------------ */

    /**
     * The base potion identity read from a {@link PotionMeta}.
     *
     * @param name     lowercase base type token (e.g. {@code "strength"})
     * @param extended the "long" flag
     * @param upgraded the "strong" flag
     */
    private record BaseType(@NotNull String name, boolean extended, boolean upgraded) {}

    /**
     * Reads the base potion type and its extended/upgraded flags cross-version.
     *
     * <p>Modern (1.20.5+, reflective): {@code getBasePotionType()} returns a
     * {@link PotionType} whose name folds the level (e.g. {@code STRONG_STRENGTH});
     * the prefix is parsed for the flags. Pre-1.20.5 (directly compiled):
     * {@code getBasePotionData()} exposes {@code isExtended()}/{@code isUpgraded()}
     * separately.</p>
     *
     * @return the base identity, or {@code null} if the meta has no base type
     */
    @SuppressWarnings("deprecation")
    private static @Nullable BaseType readBaseType(@NotNull PotionMeta meta) {
        if (GET_BASE_POTION_TYPE != null) {
            try {
                Object result = GET_BASE_POTION_TYPE.invoke(meta);
                if (result == null) {
                    return null;
                }
                String raw = ((PotionType) result).name().toLowerCase(Locale.ROOT);
                boolean extended = raw.startsWith("long_");
                boolean upgraded = raw.startsWith("strong_");
                return new BaseType(canonicalBase(raw), extended, upgraded);
            } catch (ReflectiveOperationException ignored) {
                // Fall through to the PotionData path.
            }
        }

        // Pre-1.20.5 (or modern reflection failed): PotionData carries the flags.
        try {
            org.bukkit.potion.PotionData data = meta.getBasePotionData();
            if (data == null) {
                return null;
            }
            String raw = data.getType().name().toLowerCase(Locale.ROOT);
            return new BaseType(canonicalBase(raw), data.isExtended(), data.isUpgraded());
        } catch (NoSuchMethodError legacyRemoved) {
            // A future runtime dropped the deprecated PotionData API entirely.
            return null;
        }
    }

    /**
     * Strips a {@code minecraft:} prefix and the {@code strong_}/{@code long_}
     * fold, then maps modern aliases onto the table's canonical base name.
     */
    private static @NotNull String canonicalBase(@NotNull String raw) {
        String name = raw;
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }
        if (name.startsWith("strong_")) {
            name = name.substring("strong_".length());
        } else if (name.startsWith("long_")) {
            name = name.substring("long_".length());
        }
        return BASE_ALIASES.getOrDefault(name, name);
    }

    /**
     * Resets the meta's base type to a no-effect {@code WATER} potion so the base
     * type's own (modern) effect does not also apply on top of our custom effect
     * — exactly as OCM does. Cross-version via reflective {@code setBasePotionType}
     * (modern, 1.20.5+) with a directly-compiled {@code setBasePotionData}
     * fallback (pre-1.20.5).
     */
    @SuppressWarnings("deprecation")
    private static void clearBaseType(@NotNull PotionMeta meta) {
        if (SET_BASE_POTION_TYPE != null) {
            try {
                SET_BASE_POTION_TYPE.invoke(meta, PotionType.WATER);
                return;
            } catch (ReflectiveOperationException ignored) {
                // Fall through to the PotionData path.
            }
        }
        try {
            meta.setBasePotionData(new org.bukkit.potion.PotionData(PotionType.WATER));
        } catch (NoSuchMethodError legacyRemoved) {
            // A future runtime dropped the deprecated PotionData API; the modern
            // path above should have handled it, so this is best-effort only.
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Effect-type resolution (cross-version)                            */
    /* ------------------------------------------------------------------ */

    /**
     * Resolves the {@link PotionEffectType} for a canonical base name (e.g.
     * {@code "strength"} → STRENGTH, {@code "swiftness"} → SPEED). Uses
     * {@code getByKey} reflection (Paper 1.20.5+) with a {@code getByName}
     * fallback — the same shape as {@code GoldenAppleModule#resolveType}.
     */
    @SuppressWarnings("deprecation")
    private @Nullable PotionEffectType resolveEffectType(@NotNull String base) {
        String effectKey = EFFECT_KEY_BY_BASE.get(base);
        if (effectKey == null) {
            return null;
        }

        if (GET_BY_KEY != null) {
            try {
                Object result = GET_BY_KEY.invoke(null, NamespacedKey.minecraft(effectKey));
                if (result instanceof PotionEffectType type) {
                    return type;
                }
            } catch (ReflectiveOperationException ignored) {
                // Fall through to name-based lookup.
            }
        }

        // getByName accepts the legacy Bukkit names; modern Paper keeps them as
        // aliases. The relevant legacy spellings differ for these keys.
        String legacyName = switch (effectKey) {
            case "speed" -> "SPEED";
            case "slowness" -> "SLOW";
            case "jump_boost" -> "JUMP";
            case "fire_resistance" -> "FIRE_RESISTANCE";
            case "night_vision" -> "NIGHT_VISION";
            case "water_breathing" -> "WATER_BREATHING";
            default -> effectKey.toUpperCase(Locale.ROOT);
        };
        return PotionEffectType.getByName(legacyName);
    }
}
