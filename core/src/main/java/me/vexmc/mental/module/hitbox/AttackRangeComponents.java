package me.vexmc.mental.module.hitbox;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The capability resolver and component driver for the era {@code ATTACK_RANGE}
 * item data component (1.21.5+).
 *
 * <p>This is the verified-once-at-init half of the hitbox module: it probes the
 * server for the {@code DataComponents.ATTACK_RANGE} field, and if present caches
 * the NMS handles needed to write the era {@link EraReach} component onto a held
 * weapon and to strip it again. Everything is resolved by class/field presence and
 * reflection — never by a version literal — so the same code path lights up on
 * whichever capability a given server has.</p>
 *
 * <h2>Capability (javap-verified against the matrix jars)</h2>
 * <ul>
 *   <li><b>Present</b> — {@code net.minecraft.core.component.DataComponents.ATTACK_RANGE}
 *       and the {@code net.minecraft.world.item.component.AttackRange} record
 *       (verified present on the 1.21.11 jar, ABSENT on 1.21.4). The component
 *       carries {@code (minRange, maxRange, minCreativeRange, maxCreativeRange,
 *       hitboxMargin, mobFactor)} — the six era values from {@link EraReach}.</li>
 *   <li><b>Absent</b> — every server below the component version (1.17.1–1.21.4).
 *       {@link #supported()} returns {@code false}; the module never writes a
 *       component (the attribute helper still runs on 1.20.5+).</li>
 * </ul>
 *
 * <h2>Write path (verified NMS only)</h2>
 * <p>The Paper Bukkit datacomponent API ({@code io.papermc.paper.datacomponent.item.AttackRange})
 * ships in the separate {@code paper-api} artifact and is ABSENT from the server
 * jars, so its builder shape could not be javap-verified on the matrix jars. The
 * project rule is to never ship an unverified component write, so this driver
 * writes the {@code ATTACK_RANGE} component exclusively through the fully
 * javap-verified NMS path — the {@code AttackRange(float, float, float, float,
 * float, float)} constructor and {@code ItemStack.set/remove/has(DataComponentType
 * ...)} (the same handles {@code SwordBlockComponents} uses). All reflective
 * lookups happen once in the constructor; the hot path only invokes cached
 * handles.</p>
 *
 * <p>All failures are swallowed in favour of a clean no-op: if Paper changes its
 * internals we report unsupported rather than corrupt an item.</p>
 */
final class AttackRangeComponents {

    private final boolean supported;

    /* ---- NMS handles (the verified write path) ---- */
    private final @Nullable Object attackRangeType;
    private final @Nullable Object attackRangeComponent;
    private final @Nullable Method nmsItemStackSet;
    private final @Nullable Method nmsItemStackRemove;
    private final @Nullable Method nmsItemStackHas;
    private volatile @Nullable Field craftItemStackHandleField;

    AttackRangeComponents() {
        boolean resolved = false;
        Object type = null;
        Object component = null;
        Method set = null;
        Method remove = null;
        Method has = null;

        try {
            Class<?> nmsDataComponents = Class.forName("net.minecraft.core.component.DataComponents");
            if (fieldPresent(nmsDataComponents, "ATTACK_RANGE")) {
                component = buildNmsAttackRange();
                if (component != null) {
                    type = nmsDataComponents.getField("ATTACK_RANGE").get(null);

                    Class<?> nmsItemStack = Class.forName("net.minecraft.world.item.ItemStack");
                    Class<?> nmsComponentType =
                            Class.forName("net.minecraft.core.component.DataComponentType");
                    set = findItemStackBinary(nmsItemStack, "set", nmsComponentType);
                    remove = findItemStackUnary(nmsItemStack, "remove", nmsComponentType);
                    has = findItemStackUnary(nmsItemStack, "has", nmsComponentType);

                    resolved = type != null && set != null && remove != null && has != null;
                }
            }
        } catch (Throwable absent) {
            resolved = false; // No component model — stay unsupported, all fields null.
        }

        if (!resolved) {
            type = null;
            component = null;
            set = null;
            remove = null;
            has = null;
        }

        this.supported = resolved;
        this.attackRangeType = type;
        this.attackRangeComponent = component;
        this.nmsItemStackSet = set;
        this.nmsItemStackRemove = remove;
        this.nmsItemStackHas = has;
    }

    /** Whether this server can write the {@code ATTACK_RANGE} component (1.21.5+). */
    boolean supported() {
        return supported;
    }

    @NotNull String describe() {
        return supported ? "ATTACK_RANGE via nms" : "(none)";
    }

    /* ------------------------------------------------------------------ */
    /*  Apply / strip                                                      */
    /* ------------------------------------------------------------------ */

    /**
     * Applies the era {@code ATTACK_RANGE} component to {@code stack} (a weapon).
     * Returns {@code true} if the stack was modified (the caller must write it back).
     */
    boolean apply(@Nullable ItemStack stack) {
        if (!supported || stack == null || !isWeapon(stack.getType())
                || attackRangeType == null || attackRangeComponent == null) {
            return false;
        }
        return nmsSet(stack, attackRangeType, attackRangeComponent);
    }

    /**
     * Removes the {@code ATTACK_RANGE} component this driver may have applied.
     * Returns {@code true} if the stack was modified (the caller must write it back).
     * Strips regardless of material so a no-longer-weapon stack is still cleaned.
     */
    boolean strip(@Nullable ItemStack stack) {
        if (!supported || stack == null || stack.getType() == Material.AIR
                || attackRangeType == null) {
            return false;
        }
        return nmsRemove(stack, attackRangeType);
    }

    /* ------------------------------------------------------------------ */
    /*  NMS component construction (verified)                              */
    /* ------------------------------------------------------------------ */

    /**
     * Builds {@code new AttackRange(0.0f minRange, 3.0f maxRange, 0.0f
     * minCreativeRange, 4.0f maxCreativeRange, 0.1f hitboxMargin, 1.0f mobFactor)}
     * — the era melee window from {@link EraReach}.
     *
     * <p>Constructor signature verified by javap on the 1.21.11 jar:
     * {@code public AttackRange(float, float, float, float, float, float)} over the
     * record fields {@code (minRange, maxRange, minCreativeRange, maxCreativeRange,
     * hitboxMargin, mobFactor)} in declaration order. {@code AttackRange.isInRange}
     * uses {@code [min-margin-leniency, max+margin+leniency]} eye-to-AABB, so this
     * restores the 3-block survival reach with the era ~0.1 hitbox grow.</p>
     */
    private static @Nullable Object buildNmsAttackRange() {
        try {
            Class<?> attackRange = Class.forName("net.minecraft.world.item.component.AttackRange");
            return attackRange
                    .getConstructor(float.class, float.class, float.class,
                            float.class, float.class, float.class)
                    .newInstance(
                            (float) EraReach.MIN_REACH,
                            (float) EraReach.MAX_REACH,
                            (float) EraReach.MIN_CREATIVE_REACH,
                            (float) EraReach.MAX_CREATIVE_REACH,
                            (float) EraReach.HITBOX_MARGIN,
                            (float) EraReach.MOB_FACTOR);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Reflective set/unset plumbing                                      */
    /* ------------------------------------------------------------------ */

    private boolean nmsSet(@NotNull ItemStack stack, @NotNull Object type, @NotNull Object component) {
        if (nmsItemStackSet == null) {
            return false;
        }
        try {
            Object handle = nmsHandle(stack);
            if (handle == null) {
                return false;
            }
            nmsItemStackSet.invoke(handle, type, component);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean nmsRemove(@NotNull ItemStack stack, @NotNull Object type) {
        if (nmsItemStackRemove == null) {
            return false;
        }
        try {
            Object handle = nmsHandle(stack);
            if (handle == null || !nmsHas(stack, type)) {
                return false;
            }
            nmsItemStackRemove.invoke(handle, type);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean nmsHas(@NotNull ItemStack stack, @NotNull Object type) {
        if (nmsItemStackHas == null) {
            return false;
        }
        try {
            Object handle = nmsHandle(stack);
            if (handle == null) {
                return false;
            }
            Object result = nmsItemStackHas.invoke(handle, type);
            return result instanceof Boolean bool && bool;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private @Nullable Object nmsHandle(@NotNull ItemStack stack) throws ReflectiveOperationException {
        Field field = craftItemStackHandleField;
        if (field == null) {
            Class<?> c = stack.getClass();
            while (c != null && c != Object.class) {
                try {
                    Field candidate = c.getDeclaredField("handle");
                    candidate.setAccessible(true);
                    field = candidate;
                    craftItemStackHandleField = candidate;
                    break;
                } catch (NoSuchFieldException next) {
                    c = c.getSuperclass();
                }
            }
            if (field == null) {
                return null; // Not a CraftItemStack (e.g. a Bukkit ItemStack with no handle).
            }
        }
        return field.get(stack);
    }

    /* ------------------------------------------------------------------ */
    /*  Reflection helpers                                                 */
    /* ------------------------------------------------------------------ */

    private static boolean fieldPresent(@NotNull Class<?> owner, @NotNull String name) {
        try {
            owner.getField(name);
            return true;
        } catch (NoSuchFieldException absent) {
            return false;
        }
    }

    private static @Nullable Method findItemStackBinary(
            @NotNull Class<?> itemStack, @NotNull String name, @NotNull Class<?> componentType) {
        for (Method m : itemStack.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != 2) {
                continue;
            }
            if (m.getParameterTypes()[0].isAssignableFrom(componentType)) {
                return m;
            }
        }
        return null;
    }

    private static @Nullable Method findItemStackUnary(
            @NotNull Class<?> itemStack, @NotNull String name, @NotNull Class<?> componentType) {
        for (Method m : itemStack.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != 1) {
                continue;
            }
            if (m.getParameterTypes()[0].isAssignableFrom(componentType)) {
                return m;
            }
        }
        return null;
    }

    /**
     * Whether {@code material} is a melee weapon the era reach should apply to —
     * the same suffix set OCM uses (sword/axe/pickaxe/shovel/hoe/trident/mace),
     * version-stable across the whole range. Spades are named {@code _SHOVEL} on
     * every supported version.
     */
    static boolean isWeapon(@Nullable Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        return name.endsWith("_SWORD")
                || name.endsWith("_AXE")
                || name.endsWith("_PICKAXE")
                || name.endsWith("_SHOVEL")
                || name.endsWith("_HOE")
                || name.equals("TRIDENT")
                || name.equals("MACE");
    }
}
