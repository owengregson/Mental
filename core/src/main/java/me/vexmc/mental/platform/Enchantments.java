package me.vexmc.mental.platform;

import java.lang.reflect.Field;
import org.bukkit.enchantments.Enchantment;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Cross-version enchantment constants.
 *
 * <p>Bukkit renamed enchantment constants to their Minecraft keys in 1.20.5
 * and dropped the legacy names ({@code DAMAGE_ALL} became {@code SHARPNESS}).
 * {@code KNOCKBACK} kept its name on both sides; everything else is resolved
 * by name once at class load, modern spelling first.</p>
 */
public final class Enchantments {

    private static final @Nullable Enchantment SHARPNESS = resolve("SHARPNESS", "DAMAGE_ALL");
    private static final @Nullable Enchantment PUNCH = resolve("PUNCH", "ARROW_KNOCKBACK");
    private static final @Nullable Enchantment PROTECTION =
            resolve("PROTECTION", "PROTECTION_ENVIRONMENTAL");
    private static final @Nullable Enchantment FIRE_PROTECTION =
            resolve("FIRE_PROTECTION", "PROTECTION_FIRE");
    private static final @Nullable Enchantment FEATHER_FALLING =
            resolve("FEATHER_FALLING", "PROTECTION_FALL");
    private static final @Nullable Enchantment BLAST_PROTECTION =
            resolve("BLAST_PROTECTION", "PROTECTION_EXPLOSIONS");
    private static final @Nullable Enchantment PROJECTILE_PROTECTION =
            resolve("PROJECTILE_PROTECTION", "PROTECTION_PROJECTILE");

    private Enchantments() {}

    public static @Nullable Enchantment sharpness() {
        return SHARPNESS;
    }

    public static @Nullable Enchantment punch() {
        return PUNCH;
    }

    /** Armour Protection (modern) / {@code PROTECTION_ENVIRONMENTAL} (legacy). */
    public static @Nullable Enchantment protection() {
        return PROTECTION;
    }

    /** Fire Protection (modern) / {@code PROTECTION_FIRE} (legacy). */
    public static @Nullable Enchantment fireProtection() {
        return FIRE_PROTECTION;
    }

    /** Feather Falling (modern) / {@code PROTECTION_FALL} (legacy). */
    public static @Nullable Enchantment featherFalling() {
        return FEATHER_FALLING;
    }

    /** Blast Protection (modern) / {@code PROTECTION_EXPLOSIONS} (legacy). */
    public static @Nullable Enchantment blastProtection() {
        return BLAST_PROTECTION;
    }

    /** Projectile Protection (modern) / {@code PROTECTION_PROJECTILE} (legacy). */
    public static @Nullable Enchantment projectileProtection() {
        return PROJECTILE_PROTECTION;
    }

    public static @NotNull Enchantment knockback() {
        return Enchantment.KNOCKBACK;
    }

    private static @Nullable Enchantment resolve(@NotNull String modernName, @NotNull String legacyName) {
        Enchantment modern = staticField(modernName);
        return modern != null ? modern : staticField(legacyName);
    }

    private static @Nullable Enchantment staticField(@NotNull String name) {
        try {
            Field field = Enchantment.class.getField(name);
            Object value = field.get(null);
            return value instanceof Enchantment enchantment ? enchantment : null;
        } catch (NoSuchFieldException | IllegalAccessException absent) {
            return null;
        }
    }
}
