package me.vexmc.mental.platform;

import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Cross-version single-effect lookup.
 *
 * <p>{@code LivingEntity#getPotionEffect(PotionEffectType)} — the direct
 * "does this entity have exactly this effect" accessor — is a Bukkit API method
 * only from 1.10.2; on 1.9.4 it is absent and a direct call is a
 * {@code NoSuchMethodError} at execution (javap-verified). The collection form
 * {@code getActivePotionEffects()} has existed since long before the backport's
 * floor, so the fallback scans it for the requested type — same result, no
 * modern API.</p>
 *
 * <p>The accessor is chosen ONCE at class load (never a version parse). The
 * modern path returns the exact effect instance the direct accessor would; the
 * scan fallback returns the matching {@link PotionEffect} from the active set or
 * {@code null} when the type is not present.</p>
 */
public final class PotionEffects {

    private enum Source { BUKKIT_API, ACTIVE_SCAN }

    private static final Source SOURCE = resolve();

    private PotionEffects() {}

    /** The entity's active {@code type} effect, or {@code null} when absent — via the boot-resolved path. */
    public static @Nullable PotionEffect of(@NotNull LivingEntity entity, @Nullable PotionEffectType type) {
        if (type == null) {
            return null;
        }
        if (SOURCE == Source.BUKKIT_API) {
            return entity.getPotionEffect(type);
        }
        for (PotionEffect active : entity.getActivePotionEffects()) {
            if (type.equals(active.getType())) {
                return active;
            }
        }
        return null;
    }

    /** The resolved accessor's name — for the boot report and the chain-selection unit pin. */
    public static @NotNull String describe() {
        return switch (SOURCE) {
            case BUKKIT_API -> "LivingEntity#getPotionEffect(type)";
            case ACTIVE_SCAN -> "scan getActivePotionEffects()";
        };
    }

    private static Source resolve() {
        try {
            LivingEntity.class.getMethod("getPotionEffect", PotionEffectType.class);
            return Source.BUKKIT_API;
        } catch (NoSuchMethodException absent) {
            return Source.ACTIVE_SCAN;
        }
    }
}
