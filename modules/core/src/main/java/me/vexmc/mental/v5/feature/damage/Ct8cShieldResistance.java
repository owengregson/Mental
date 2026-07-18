package me.vexmc.mental.v5.feature.damage;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import me.vexmc.mental.platform.Attributes;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The {@code +0.5} knockback-resistance lever a CT8c shield grants while blocking
 * (spec §2.6: "Shields grant 0.5 knockback resistance while blocking"). It feeds
 * the desk's context through the ONE seam both knockback paths already read — the
 * {@code GENERIC_KNOCKBACK_RESISTANCE} attribute: the per-tick {@code PlayerView}
 * freeze samples it for the netty pre-send, and {@code EntityStates.captureVictim}
 * reads it live for the region recompute, so the modern formula's internal
 * {@code (1 − r)} scalar halves the knock on both. The ct8c profile's
 * {@code shield-blocking-cancels: false} leaves the knock uncancelled precisely so
 * this reduction — not an outright cancel — is what a blocked hit ships.
 *
 * <p>A {@code MULTIPLY}-free additive {@link AttributeModifier.Operation#ADD_NUMBER}
 * modifier under a fixed {@code mental:ct8c-shield-kbresist} identity, added while a
 * player holds the crouch-to-shield posture and stripped when they leave it (and on
 * quit/death/world-change/disable). Removal is by-identity, so a modifier a crash
 * leaked into saved NBT is swept on the next join too.</p>
 *
 * <p><b>Cross-version construction.</b> {@code AttributeModifier}'s ctor drifted
 * from {@code (UUID, String, double, Operation)} (≤1.20.6) to
 * {@code (NamespacedKey, double, Operation)} (1.21+), exactly the drift the platform
 * {@code AttributeModifiers} absorbs for the combo-reach lever. That class is pinned
 * to a single identity/operation and is loaded only behind a 1.20.5+ probe, so it
 * cannot serve a knockback-resistance modifier that must work down to 1.9.4 — hence
 * this dedicated twin. It is deliberately class-load-safe on the legacy tier: no
 * {@code NamespacedKey} is referenced in any field, signature, or ctor probe (the
 * modern ctor is matched by parameter-type NAME), so {@code NamespacedKey} never
 * links on a sub-1.12 server that lacks it — only the legacy UUID ctor path runs
 * there. <i>Follow-up:</i> this reflection belongs in a platform seam (a generic
 * {@code AttributeModifiers.knockbackResistance(amount)}); it lives here only to
 * keep Task E within its file-ownership boundary.</p>
 */
final class Ct8cShieldResistance {

    /** Spec §2.6: a blocking shield adds 0.5 to the victim's knockback resistance. */
    static final double AMOUNT = 0.5;

    private static final String IDENTITY = "mental:ct8c-shield-kbresist";

    /** The deterministic legacy identity UUID — a leaked modifier from a prior boot carries the same value. */
    private static final UUID IDENTITY_UUID =
            UUID.nameUUIDFromBytes(IDENTITY.getBytes(StandardCharsets.UTF_8));

    /** The modern (1.21+) {@code (NamespacedKey, double, Operation)} ctor, matched by name — null below ~1.21. */
    private static final @Nullable Constructor<?> MODERN_CTOR = modernCtor();

    /** The legacy (≤1.20.6) {@code (UUID, String, double, Operation)} ctor — null once removed above ~1.20.6. */
    private static final @Nullable Constructor<?> LEGACY_CTOR = legacyCtor();

    /** {@code AttributeModifier#getKey()} — the modern identity accessor, absent on the floor API. */
    private static final @Nullable Method KEY_ACCESSOR = accessor();

    /** Whether a construction shape and the knockback-resistance attribute both resolved on this runtime. */
    boolean supported() {
        return (MODERN_CTOR != null || LEGACY_CTOR != null) && Attributes.knockbackResistance() != null;
    }

    /** Adds the resistance lever to {@code player} — by-identity idempotent (a re-apply never stacks). */
    void apply(@NotNull Player player) {
        AttributeInstance instance = instanceOf(player);
        if (instance == null) {
            return;
        }
        removeMatching(instance); // clear any prior identity first — never stack a second lever
        AttributeModifier modifier = build();
        if (modifier != null) {
            try {
                instance.addModifier(modifier);
            } catch (RuntimeException alreadyPresent) {
                // A leaked-then-reloaded modifier of the same identity can reject the add;
                // removeMatching above normally clears it, so this is a benign belt.
            }
        }
    }

    /** Strips the resistance lever from {@code player} — by-identity, so it clears a leaked one too. */
    void remove(@NotNull Player player) {
        AttributeInstance instance = instanceOf(player);
        if (instance != null) {
            removeMatching(instance);
        }
    }

    /** Strips the lever from every online player (the disable teardown — B12). */
    void stripAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            remove(player);
        }
    }

    private static @Nullable AttributeInstance instanceOf(@NotNull Player player) {
        Attribute attribute = Attributes.knockbackResistance();
        return attribute == null ? null : player.getAttribute(attribute);
    }

    private static @Nullable AttributeModifier build() {
        AttributeModifier.Operation operation = AttributeModifier.Operation.ADD_NUMBER;
        try {
            if (MODERN_CTOR != null) {
                return (AttributeModifier) MODERN_CTOR.newInstance(modernKey(), AMOUNT, operation);
            }
            if (LEGACY_CTOR != null) {
                return (AttributeModifier) LEGACY_CTOR.newInstance(IDENTITY_UUID, IDENTITY, AMOUNT, operation);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError broken) {
            // Both ctor shapes were probed present at load; a failure here is a genuinely
            // broken runtime — degrade to no lever rather than throw (the hit still lands).
            return null;
        }
        return null;
    }

    /** Removes every modifier carrying our identity; safe on a snapshot (mutating the live view is not). */
    private static void removeMatching(@NotNull AttributeInstance instance) {
        List<AttributeModifier> ours = new ArrayList<>();
        for (AttributeModifier modifier : instance.getModifiers()) {
            if (isOurs(modifier)) {
                ours.add(modifier);
            }
        }
        for (AttributeModifier modifier : ours) {
            instance.removeModifier(modifier);
        }
    }

    /** Whether {@code modifier} is ours, matched on whichever identity this runtime exposes. */
    private static boolean isOurs(@NotNull AttributeModifier modifier) {
        // Modern identity (1.21+): the NamespacedKey via Keyed#getKey (reflective — absent
        // on the floor). Guarded so the NamespacedKey compare never links on a legacy runtime.
        if (KEY_ACCESSOR != null) {
            try {
                if (modernKey().equals(KEY_ACCESSOR.invoke(modifier))) {
                    return true;
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
                // getKey unusable — fall through to the legacy identity.
            }
        }
        try {
            if (IDENTITY.equals(modifier.getName())) {
                return true;
            }
        } catch (RuntimeException | LinkageError absent) {
            // getName removed on some modern runtimes.
        }
        try {
            if (IDENTITY_UUID.equals(modifier.getUniqueId())) {
                return true;
            }
        } catch (RuntimeException | LinkageError absent) {
            // getUniqueId removed on some modern runtimes.
        }
        return false;
    }

    /**
     * A fresh {@code mental:ct8c-shield-kbresist} {@link org.bukkit.NamespacedKey},
     * returned as {@link Object} so the type never appears in a signature. Called
     * ONLY on 1.12+ paths (the modern ctor / the getKey identity), so the class link
     * is never forced on a sub-1.12 server.
     */
    private static @NotNull Object modernKey() {
        return new org.bukkit.NamespacedKey("mental", "ct8c-shield-kbresist");
    }

    /** The modern ctor matched by parameter-type NAME, so no {@code NamespacedKey} class-literal is linked. */
    private static @Nullable Constructor<?> modernCtor() {
        for (Constructor<?> ctor : AttributeModifier.class.getConstructors()) {
            Class<?>[] parameters = ctor.getParameterTypes();
            if (parameters.length == 3
                    && "org.bukkit.NamespacedKey".equals(parameters[0].getName())
                    && parameters[1] == double.class
                    && parameters[2] == AttributeModifier.Operation.class) {
                return ctor;
            }
        }
        return null;
    }

    private static @Nullable Constructor<?> legacyCtor() {
        try {
            return AttributeModifier.class.getConstructor(
                    UUID.class, String.class, double.class, AttributeModifier.Operation.class);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    private static @Nullable Method accessor() {
        try {
            return AttributeModifier.class.getMethod("getKey");
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }
}
