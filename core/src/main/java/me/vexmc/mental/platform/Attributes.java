package me.vexmc.mental.platform;

import java.lang.reflect.Field;
import java.util.Optional;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Cross-version attribute constants.
 *
 * <p>{@code org.bukkit.attribute.Attribute} was an enum with {@code GENERIC_}
 * prefixed constants through 1.21.1 and became a registry-backed interface
 * with unprefixed constants in 1.21.3 — an outright binary break in both
 * directions. Constants are therefore resolved by name once at class load,
 * trying the modern spelling first, and cached; lookups after boot are plain
 * static field reads.</p>
 */
public final class Attributes {

    private static final @Nullable Attribute ATTACK_DAMAGE =
            resolve("ATTACK_DAMAGE", "GENERIC_ATTACK_DAMAGE");
    private static final @Nullable Attribute ATTACK_SPEED =
            resolve("ATTACK_SPEED", "GENERIC_ATTACK_SPEED");
    private static final @Nullable Attribute KNOCKBACK_RESISTANCE =
            resolve("KNOCKBACK_RESISTANCE", "GENERIC_KNOCKBACK_RESISTANCE");
    private static final @Nullable Attribute GRAVITY =
            resolve("GRAVITY", "GENERIC_GRAVITY");
    private static final @Nullable Attribute ENTITY_INTERACTION_RANGE =
            resolve("ENTITY_INTERACTION_RANGE", "PLAYER_ENTITY_INTERACTION_RANGE");
    private static final @Nullable Attribute MAX_HEALTH =
            resolve("MAX_HEALTH", "GENERIC_MAX_HEALTH");

    private Attributes() {}

    public static @Nullable Attribute attackDamage() {
        return ATTACK_DAMAGE;
    }

    public static @Nullable Attribute attackSpeed() {
        return ATTACK_SPEED;
    }

    public static @Nullable Attribute knockbackResistance() {
        return KNOCKBACK_RESISTANCE;
    }

    /** Absent below 1.20.5 — callers fall back to vanilla's 0.08. */
    public static @Nullable Attribute gravity() {
        return GRAVITY;
    }

    /** Absent below 1.20.5 — callers fall back to the classic 3.0 attack reach. */
    public static @Nullable Attribute entityInteractionRange() {
        return ENTITY_INTERACTION_RANGE;
    }

    /**
     * {@code MAX_HEALTH} (modern, 1.21.3+) / {@code GENERIC_MAX_HEALTH} (legacy,
     * 1.17.1–1.21.2) — present on all supported versions.
     *
     * <p>Prefer {@link #valueOr} with a fallback of 20.0 (vanilla default) rather
     * than the deprecated {@link org.bukkit.entity.LivingEntity#getMaxHealth()}.</p>
     */
    public static @Nullable Attribute maxHealth() {
        return MAX_HEALTH;
    }

    /** The attribute's current value, or {@code fallback} when absent on this entity or version. */
    public static double valueOr(@NotNull LivingEntity entity, @Nullable Attribute attribute, double fallback) {
        if (attribute == null) {
            return fallback;
        }
        AttributeInstance instance = entity.getAttribute(attribute);
        return instance != null ? instance.getValue() : fallback;
    }

    static @NotNull Optional<Attribute> lookup(@NotNull String modernName, @NotNull String legacyName) {
        return Optional.ofNullable(resolve(modernName, legacyName));
    }

    private static @Nullable Attribute resolve(@NotNull String modernName, @NotNull String legacyName) {
        Attribute modern = staticField(modernName);
        return modern != null ? modern : staticField(legacyName);
    }

    private static @Nullable Attribute staticField(@NotNull String name) {
        try {
            Field field = Attribute.class.getField(name);
            Object value = field.get(null);
            return value instanceof Attribute attribute ? attribute : null;
        } catch (NoSuchFieldException | IllegalAccessException absent) {
            return null;
        }
    }
}
