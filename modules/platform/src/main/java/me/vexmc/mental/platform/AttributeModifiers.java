package me.vexmc.mental.platform;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A version-blind constructor/remover for ONE fixed-identity attribute modifier —
 * {@code mental:combo-reach}, the combo-hold reach handicap's lever (design §1).
 *
 * <p>{@code AttributeModifier}'s construction API drifts across the range: it was
 * built from a {@code (UUID, String, amount, Operation)} tuple through ~1.20.6 and
 * switched to a {@code (NamespacedKey, amount, Operation)} tuple at 1.21 (the UUID
 * ctor deprecated, then gone), mirroring the {@code Attribute} enum→interface break
 * {@link Attributes} already absorbs. {@code core} compiles against the 1.17.1 floor
 * where only the UUID ctor exists, so BOTH shapes are resolved by reflection once at
 * class load (modern first, then legacy) — never a typed overload above the floor.
 * The {@link AttributeModifier.Operation} enum is stable since 1.9, so it is named
 * directly.</p>
 *
 * <h2>The lever</h2>
 * <p>The modifier is {@link AttributeModifier.Operation#MULTIPLY_SCALAR_1} — the
 * multiply-TOTAL operation — with amount {@code scale − 1}, so the effective range
 * is {@code base × scale} regardless of any third-party base (an additive modifier,
 * never a base rewrite). At the shipped default {@code scale 0.87} the era 3.0 reach
 * becomes 2.61.</p>
 *
 * <h2>Idempotent by identity</h2>
 * <p>The identity is fixed (a deterministic UUID/name on the legacy shape, a
 * {@link NamespacedKey} on the modern one), so {@link #removeMatching} is a
 * by-identity sweep: it strips EVERY modifier that carries our identity, whether we
 * added it this session or a crash leaked it into the player's saved NBT. Both the
 * lifecycle removal and the enable/join stale-sweep route through it.</p>
 *
 * <p>Pure {@link NamespacedKey}/reflection lives entirely in method bodies and lazy
 * statics here — this class is loaded only behind the caller's 1.20.5+ attribute
 * probe (the {@code NappleKeyed} precedent), so its {@link NamespacedKey} constant
 * never links on a sub-1.12 server that has no such type.</p>
 */
public final class AttributeModifiers {

    /** The modern (1.21+) identity: {@code mental:combo-reach}. */
    private static final NamespacedKey COMBO_REACH_KEY = new NamespacedKey("mental", "combo-reach");

    /** The legacy (≤1.20.6) identity name — matched against {@code getName()} on those runtimes. */
    private static final String COMBO_REACH_NAME = "mental:combo-reach";

    /**
     * The legacy identity UUID — a deterministic name-hash so removal-by-uuid is
     * idempotent across restarts (a leaked modifier from a previous boot carries the
     * same value).
     */
    private static final UUID COMBO_REACH_UUID =
            UUID.nameUUIDFromBytes(COMBO_REACH_NAME.getBytes(StandardCharsets.UTF_8));

    /** The modern ctor {@code (NamespacedKey, double, Operation)}, or null below ~1.21. */
    private static final @Nullable Constructor<AttributeModifier> MODERN_CTOR =
            ctor(NamespacedKey.class, double.class, AttributeModifier.Operation.class);

    /** The legacy ctor {@code (UUID, String, double, Operation)}, or null once removed above ~1.20.6. */
    private static final @Nullable Constructor<AttributeModifier> LEGACY_CTOR =
            ctor(UUID.class, String.class, double.class, AttributeModifier.Operation.class);

    /**
     * {@code AttributeModifier#getKey()} — the modern (1.21+) {@link NamespacedKey}
     * identity accessor, absent on the floor API, so it is invoked reflectively.
     */
    private static final @Nullable Method KEY_ACCESSOR = accessor("getKey");

    private AttributeModifiers() {}

    /** Whether a construction shape resolved on this runtime (belt to the attribute probe). */
    public static boolean supported() {
        return MODERN_CTOR != null || LEGACY_CTOR != null;
    }

    /**
     * The multiply-total amount for a target {@code scale}: {@code scale − 1}, so
     * {@code MULTIPLY_SCALAR_1} yields {@code base × scale}. Pure — unit-pinned.
     */
    public static double amountFor(double scale) {
        return scale - 1.0;
    }

    /**
     * A fresh {@code mental:combo-reach} modifier scaling the total by {@code scale}
     * (via {@link #amountFor}), or null when no construction shape resolved. The
     * modern shape is preferred; the legacy shape carries the deterministic
     * UUID/name identity.
     */
    public static @Nullable AttributeModifier comboReach(double scale) {
        double amount = amountFor(scale);
        AttributeModifier.Operation operation = AttributeModifier.Operation.MULTIPLY_SCALAR_1;
        try {
            if (MODERN_CTOR != null) {
                return MODERN_CTOR.newInstance(COMBO_REACH_KEY, amount, operation);
            }
            if (LEGACY_CTOR != null) {
                return LEGACY_CTOR.newInstance(COMBO_REACH_UUID, COMBO_REACH_NAME, amount, operation);
            }
        } catch (ReflectiveOperationException | RuntimeException unexpected) {
            // Both ctor shapes were probed present at load; a failure here is a
            // genuinely broken runtime — degrade to no lever rather than throw.
            return null;
        }
        return null;
    }

    /**
     * Strips every {@code mental:combo-reach} modifier from {@code instance} —
     * idempotent by identity, so it clears both a live handicap and a stale one a
     * crash leaked into saved NBT. Returns whether anything was removed. The caller
     * runs this on the entity's owning region thread.
     */
    public static boolean removeMatching(@NotNull AttributeInstance instance) {
        // Snapshot first: removing while iterating the live modifier view is unsafe.
        List<AttributeModifier> stale = new ArrayList<>();
        for (AttributeModifier modifier : instance.getModifiers()) {
            if (isComboReach(modifier)) {
                stale.add(modifier);
            }
        }
        for (AttributeModifier modifier : stale) {
            instance.removeModifier(modifier);
        }
        return !stale.isEmpty();
    }

    /** Whether {@code modifier} is ours, matched on whichever identity this runtime exposes. */
    private static boolean isComboReach(@NotNull AttributeModifier modifier) {
        // Modern identity: the NamespacedKey (Keyed#getKey, reflective — absent on the
        // floor). Best-effort — a failure just falls through to the legacy identity.
        if (KEY_ACCESSOR != null) {
            try {
                if (COMBO_REACH_KEY.equals(KEY_ACCESSOR.invoke(modifier))) {
                    return true;
                }
            } catch (ReflectiveOperationException | RuntimeException absent) {
                // getKey unusable on this runtime — fall through.
            }
        }
        // Legacy identity: the name and the deterministic UUID (both deprecated/removed
        // on modern, so each is guarded independently).
        try {
            if (COMBO_REACH_NAME.equals(modifier.getName())) {
                return true;
            }
        } catch (RuntimeException | LinkageError absent) {
            // getName removed/unsupported on some modern runtimes.
        }
        try {
            if (COMBO_REACH_UUID.equals(modifier.getUniqueId())) {
                return true;
            }
        } catch (RuntimeException | LinkageError absent) {
            // getUniqueId removed/unsupported on some modern runtimes.
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Constructor<AttributeModifier> ctor(@NotNull Class<?>... signature) {
        try {
            return (Constructor<AttributeModifier>) AttributeModifier.class.getConstructor(signature);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    private static @Nullable Method accessor(@NotNull String name) {
        try {
            return AttributeModifier.class.getMethod(name);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }
}
