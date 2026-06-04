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

    private Enchantments() {}

    public static @Nullable Enchantment sharpness() {
        return SHARPNESS;
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
