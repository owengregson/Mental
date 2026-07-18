package me.vexmc.mental.platform;

import java.lang.reflect.Field;
import java.util.Optional;
import me.vexmc.mental.kernel.model.EntityState;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
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
    private static final @Nullable Attribute MOVEMENT_SPEED =
            resolve("MOVEMENT_SPEED", "GENERIC_MOVEMENT_SPEED");
    private static final @Nullable Attribute GRAVITY =
            resolve("GRAVITY", "GENERIC_GRAVITY");
    private static final @Nullable Attribute ENTITY_INTERACTION_RANGE =
            resolve("ENTITY_INTERACTION_RANGE", "PLAYER_ENTITY_INTERACTION_RANGE");
    private static final @Nullable Attribute MAX_HEALTH =
            resolve("MAX_HEALTH", "GENERIC_MAX_HEALTH");
    private static final @Nullable Attribute ARMOR =
            resolve("ARMOR", "GENERIC_ARMOR");
    private static final @Nullable Attribute ARMOR_TOUGHNESS =
            resolve("ARMOR_TOUGHNESS", "GENERIC_ARMOR_TOUGHNESS");

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

    /**
     * {@code MOVEMENT_SPEED} (modern, 1.21.3+) / {@code GENERIC_MOVEMENT_SPEED}
     * (legacy, 1.17.1–1.21.2) — present on every supported version (the
     * attribute API exists from 1.9). Its {@link #valueOr} is the EFFECTIVE
     * value (base × sprint × Speed/Slowness modifiers). Speed-conformal knockback
     * reads it through {@link #movementSpeedWalkNormalized}, which strips the
     * sprint modifier so the pace factor is stance-agnostic. Absent only
     * pre-attribute-API (below the runtime floor) — callers fall back to {@link
     * me.vexmc.mental.kernel.model.EntityState#MOVE_SPEED_UNAVAILABLE}.
     */
    public static @Nullable Attribute movementSpeed() {
        return MOVEMENT_SPEED;
    }

    /**
     * The exact vanilla sprint movement-speed multiplier:
     * {@code LivingEntity.SPEED_MODIFIER_SPRINTING} is the float {@code 0.3f}
     * applied as an {@code ADD_MULTIPLIED_TOTAL} modifier, so the effective
     * movement speed a sprinting entity reads is {@code base × (1 + 0.3f)} =
     * {@code × 1.30000001192092896} (javap-verified on 1.21.11 and 1.12.2). It is
     * spelt {@code 1.0 + (double) 0.3f} so the widening is byte-exact to the
     * server's own arithmetic.
     */
    private static final double SPRINT_MODIFIER = 1.0 + (double) 0.3f;

    /**
     * The entity's WALK-STANCE-NORMALIZED movement-speed attribute — the value
     * speed-conformal knockback (pace scaling) consumes for the attacker. It
     * reads {@code isSprinting()} and the effective attribute value back-to-back
     * on the caller's (owning) thread and divides the sprint modifier back out
     * when sprinting, so the result is the same whether or not the attacker's
     * stance agrees between the wire and the server.
     *
     * <p><b>Why the pair-read is coherent.</b> The sprint flag and the ×1.3
     * modifier move together <em>inside</em> {@code LivingEntity.setSprinting}, so
     * a same-thread pair always sees both or neither — {@code isSprinting()} true
     * iff the modifier is present in the effective value. Dividing out the ONLY
     * stance-churning term leaves just genuine speed (base, Speed/Slowness), so a
     * wire-vs-server stance disagreement can no longer skew the pace factor (the
     * 2.4.0 pace desync, F1: a wire-fresh sprint boolean was paired with an
     * attribute captured a different instant, dividing {@code 0.10/0.13}). The
     * {@link EntityState#MOVE_SPEED_UNAVAILABLE} sentinel (and any non-positive
     * value) passes through UN-divided — pace resolves it to the walk baseline
     * (factor 1.0) regardless.</p>
     */
    public static double movementSpeedWalkNormalized(@NotNull LivingEntity entity) {
        double effective = valueOr(entity, movementSpeed(), EntityState.MOVE_SPEED_UNAVAILABLE);
        if (effective <= 0.0) {
            return effective; // sentinel / absent ⇒ resolved to the walk baseline downstream
        }
        boolean sprinting = entity instanceof Player player && player.isSprinting();
        return sprinting ? effective / SPRINT_MODIFIER : effective;
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

    /**
     * {@code ARMOR} (modern, 1.21.3+) / {@code GENERIC_ARMOR} (legacy,
     * 1.17.1–1.21.2) — present on all supported versions. The summed worn-armour
     * defence points; the era model reads this directly and ignores
     * {@code ARMOR_TOUGHNESS} (era has no toughness).
     *
     * <p>Prefer {@link #valueOr} with a fallback of 0.0 (no armour).</p>
     */
    public static @Nullable Attribute armor() {
        return ARMOR;
    }

    /**
     * {@code ARMOR_TOUGHNESS} (modern, 1.21.3+) / {@code GENERIC_ARMOR_TOUGHNESS} (legacy) — present on all
     * supported versions. The 1.8 model has no toughness; the era tooltip strips this line from armour so the
     * display matches the era math (which ignores it).
     */
    public static @Nullable Attribute armorToughness() {
        return ARMOR_TOUGHNESS;
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
