package me.vexmc.mental.v5.platform;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import me.vexmc.mental.common.platform.ServerEnvironment;
import me.vexmc.mental.kernel.math.EraReach;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The boot-probed NMS adapter for the era {@code ATTACK_RANGE} item data
 * component (the retired {@code module.hitbox.AttackRangeComponents} on the v5
 * {@link PlatformProbe}). It is the second, weapon-scoped reach lever the
 * {@code HitboxUnit} pulls on top of the {@code ENTITY_INTERACTION_RANGE}
 * attribute: where the component exists (Minecraft 1.21.5+), a held melee weapon
 * gets the era {@link EraReach} window (max_reach 3.0, hitbox_margin 0.1); where
 * it does not (every version below), the adapter is a typed absence and the unit
 * relies on the attribute alone (or, below 1.20.5, is a documented no-op).
 *
 * <h2>Loud-fail (B10)</h2>
 * <p>A version that <em>should</em> expose the component (at or above the
 * expected first version, 1.21.5) but does not resolve is a mapping break, logged
 * loudly once at boot before degrading to unsupported. Hot-path reflective slips
 * still degrade to a clean no-op — never corrupting an item — the safety
 * invariant the retired driver established (the write path is the same
 * javap-verified NMS handle path {@link SwordBlockAdapter} uses; the Paper
 * datacomponent {@code AttackRange} builder was never javap-verified on the
 * matrix jars, so this driver writes exclusively through NMS).</p>
 *
 * <h2>Honest limit (carried forward from the driver's contract)</h2>
 * <p>The CLIENT picks the melee target and sends a fixed entity id; the server
 * resolves it verbatim with no attack-path raytrace. This component tunes the
 * reach GATE and the targeting MARGIN only — it cannot reproduce the 1.7 client's
 * wider target-selection geometry. The net effect is era-correct reach distance
 * plus the small era hitbox margin.</p>
 */
public final class AttackRangeAdapter {

    /** The version the {@code ATTACK_RANGE} component first appears (verified absent 1.21.4, present 1.21.11). */
    private static final int ATTACK_RANGE_SINCE_MAJOR = 1;
    private static final int ATTACK_RANGE_SINCE_MINOR = 21;
    private static final int ATTACK_RANGE_SINCE_PATCH = 5;

    private final boolean supported;

    private final @Nullable Object attackRangeType;
    private final @Nullable Object attackRangeComponent;
    private final @Nullable Method nmsItemStackSet;
    private final @Nullable Method nmsItemStackRemove;
    private final @Nullable Method nmsItemStackHas;
    private volatile @Nullable Field craftItemStackHandleField;

    /**
     * Boot-probes the adapter and loud-logs a mapping break (the component is
     * expected on this version but did not resolve).
     */
    public static @NotNull AttackRangeAdapter probe(
            @NotNull ServerEnvironment environment, @NotNull Consumer<String> log) {
        AttackRangeAdapter adapter = new AttackRangeAdapter();
        boolean expected = environment.isAtLeast(
                ATTACK_RANGE_SINCE_MAJOR, ATTACK_RANGE_SINCE_MINOR, ATTACK_RANGE_SINCE_PATCH);
        if (expected && !adapter.supported) {
            log.accept("platform-probe: the ATTACK_RANGE item component (DataComponents.ATTACK_RANGE) "
                    + "did not resolve on " + environment.describe()
                    + " — a mapping break; era hitbox margin falls back to the interaction-range attribute.");
        }
        return adapter;
    }

    private AttackRangeAdapter() {
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
    public boolean supported() {
        return supported;
    }

    public @NotNull String describe() {
        return supported ? "ATTACK_RANGE via nms" : "(none)";
    }

    /* ------------------------------------------------------------------ */
    /*  Apply / strip                                                      */
    /* ------------------------------------------------------------------ */

    /**
     * Applies the era {@code ATTACK_RANGE} component to {@code stack} (a weapon).
     * Returns {@code true} if the stack was modified (the caller must write it back).
     */
    public boolean apply(@Nullable ItemStack stack) {
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
    public boolean strip(@Nullable ItemStack stack) {
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
     * Builds {@code new AttackRange(minRange, maxRange, minCreativeRange,
     * maxCreativeRange, hitboxMargin, mobFactor)} from the kernel {@link EraReach}
     * constants — the era melee window. Constructor signature verified by javap on
     * the 1.21.11 jar: {@code public AttackRange(float, float, float, float, float,
     * float)} over the record fields in declaration order.
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
                return null; // Not a CraftItemStack (a plain Bukkit ItemStack with no handle).
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
     * Whether {@code material} is a melee weapon the era reach applies to — the
     * same suffix set the retired driver used (sword/axe/pickaxe/shovel/hoe/
     * trident/mace), version-stable across the whole range.
     */
    public static boolean isWeapon(@Nullable Material material) {
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
