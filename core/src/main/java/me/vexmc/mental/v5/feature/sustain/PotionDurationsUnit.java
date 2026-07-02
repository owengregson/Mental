package me.vexmc.mental.v5.feature.sustain;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import me.vexmc.mental.kernel.math.PotionDurations;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Restores the pre-1.9 potion DURATIONS (the retired
 * {@code module.potion.PotionDurationModule} on the v5 seam) from the pure kernel
 * {@link PotionDurations} table. Only the DURATION is restored; the vanilla
 * amplifier is kept (level I = 0, strong = 1) — the Strength/Weakness damage
 * VALUES are the separate {@code old-potion-values} feature.
 *
 * <p>B13 terminal-event application (never a speculative hand mutation):</p>
 * <ul>
 *   <li><b>drink</b> — {@link PlayerItemConsumeEvent}: the confirmed consume
 *       rewrites the consumed item so the applied effect uses the era duration;</li>
 *   <li><b>splash / lingering</b> — {@link ProjectileLaunchEvent}: the ALREADY
 *       thrown {@link ThrownPotion} entity's item is rewritten (covers a player
 *       throw and a dispenser), so a cancelled right-click can never corrupt an
 *       inventory item.</li>
 * </ul>
 */
public final class PotionDurationsUnit implements FeatureUnit, Listener {

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

    private static final Map<String, String> BASE_ALIASES = Map.of(
            "speed", "swiftness",
            "jump", "leaping",
            "slow", "slowness");

    private static final @Nullable Method GET_BY_KEY;
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

    @Override
    public Feature descriptor() {
        return Feature.POTION_DURATIONS;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        scope.listen(this);
    }

    /* -------------------------------- drink ------------------------------- */

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrink(@NotNull PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.POTION) {
            return;
        }
        if (adjustPotion(item, false)) {
            event.setItem(item); // the confirmed consume reads the rewritten meta
        }
    }

    /* --------------------------- splash / lingering ----------------------- */

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onThrow(@NotNull ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof ThrownPotion potion)) {
            return;
        }
        ItemStack item = potion.getItem();
        if (adjustPotion(item, true)) {
            potion.setItem(item); // rewrite the actual thrown entity, not a hand item
        }
    }

    /* ------------------------------ rewrite ------------------------------- */

    private boolean adjustPotion(@Nullable ItemStack item, boolean splash) {
        if (item == null || !(item.getItemMeta() instanceof PotionMeta meta)) {
            return false;
        }
        BaseType base = readBaseType(meta);
        if (base == null) {
            return false;
        }
        int eraTicks = PotionDurations.eraDurationTicks(base.name(), base.extended(), base.upgraded(), splash);
        if (eraTicks == PotionDurations.NO_OVERRIDE) {
            return false;
        }
        PotionEffectType effectType = resolveEffectType(base.name());
        if (effectType == null) {
            return false;
        }
        int amplifier = base.upgraded() ? 1 : 0;
        meta.addCustomEffect(new PotionEffect(effectType, eraTicks, amplifier), true);
        clearBaseType(meta);
        item.setItemMeta(meta);
        return true;
    }

    private record BaseType(@NotNull String name, boolean extended, boolean upgraded) {}

    @SuppressWarnings("deprecation")
    private static @Nullable BaseType readBaseType(@NotNull PotionMeta meta) {
        if (GET_BASE_POTION_TYPE != null) {
            try {
                Object result = GET_BASE_POTION_TYPE.invoke(meta);
                if (result == null) {
                    return null;
                }
                String raw = ((PotionType) result).name().toLowerCase(Locale.ROOT);
                return new BaseType(canonicalBase(raw), raw.startsWith("long_"), raw.startsWith("strong_"));
            } catch (ReflectiveOperationException ignored) {
                // Fall through to the PotionData path.
            }
        }
        try {
            org.bukkit.potion.PotionData data = meta.getBasePotionData();
            if (data == null) {
                return null;
            }
            String raw = data.getType().name().toLowerCase(Locale.ROOT);
            return new BaseType(canonicalBase(raw), data.isExtended(), data.isUpgraded());
        } catch (NoSuchMethodError legacyRemoved) {
            return null;
        }
    }

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
            // best-effort — the modern path should have handled it
        }
    }

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
