package me.vexmc.mental.v5.platform;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import me.vexmc.mental.platform.ServerEnvironment;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The boot-probed item-component adapter for the Combat Test 8c consumables
 * (spec §2.7/§2.8), modelled on {@link AttackRangeAdapter}/{@code
 * SwordBlockAdapter}. Where the modern component model exists (Minecraft
 * 1.20.5+) it writes two era components through the javap-verified NMS handle
 * path:
 *
 * <ul>
 *   <li><b>MAX_STACK_SIZE</b> — drinkable potions stack to 16 and snowballs to
 *       64 (spec §2.8/§2.10).</li>
 *   <li><b>CONSUMABLE</b> — {@code consume_seconds = 1.0} (20 ticks, the era
 *       drink duration; vanilla 1.6s/32 ticks) with the {@code DRINK} animation
 *       (spec §2.7).</li>
 * </ul>
 *
 * <h2>Version posture (B10)</h2>
 * <p>Below 1.20.5 there is no component model — the whole {@code
 * ct8c-consumables} feature is a documented no-op (the owning unit logs the loud
 * degrade). A 1.20.5+ server that does NOT resolve the components is a mapping
 * break, logged loudly here before degrading. Hot-path reflective slips degrade
 * to a clean no-op — an item is never corrupted (the safety invariant the
 * sibling adapters established).</p>
 *
 * <h2>Honest limits</h2>
 * <p>This stamps per stack (the {@code AttackRangeAdapter} model the plan
 * specifies), not the item prototype, so two like potions merge into a 16-stack
 * only once both carry the component; a freshly brewed, not-yet-stamped potion
 * merges after it is next handled. The drink-duration override rebuilds the
 * {@code CONSUMABLE} with the {@code DRINK} animation, so a stew keeps its own
 * eat animation/sound rather than being reshaped — the 20-tick duration is the
 * mechanic, the animation is cosmetic.</p>
 */
public final class Ct8cConsumableAdapter {

    /** Drinkable potions stack to 16 (spec §2.8). */
    public static final int POTION_STACK_SIZE = 16;
    /** Snowballs stack to 64 (spec §2.10). */
    public static final int SNOWBALL_STACK_SIZE = 64;

    private final boolean supported;

    private final @Nullable Object maxStackType;
    private final @Nullable Object consumableType;
    private final @Nullable Object drinkConsumable;
    private final @Nullable Method nmsItemStackSet;
    private final @Nullable Method nmsItemStackRemove;
    private final @Nullable Method nmsItemStackHas;
    private volatile @Nullable Field craftItemStackHandleField;

    /** The version the item-component model first appears (mappings flip; components land 1.20.5). */
    private static final int COMPONENTS_SINCE_MAJOR = 1;
    private static final int COMPONENTS_SINCE_MINOR = 20;
    private static final int COMPONENTS_SINCE_PATCH = 5;

    /**
     * Boot-probes the adapter, loud-logging a mapping break (components expected
     * on this version but did not resolve).
     */
    public static @NotNull Ct8cConsumableAdapter probe(
            @NotNull ServerEnvironment environment, @NotNull Consumer<String> log) {
        Ct8cConsumableAdapter adapter = new Ct8cConsumableAdapter();
        boolean expected = environment.isAtLeast(
                COMPONENTS_SINCE_MAJOR, COMPONENTS_SINCE_MINOR, COMPONENTS_SINCE_PATCH);
        if (expected && !adapter.supported) {
            log.accept("platform-probe: the CT8c consumable components (DataComponents.MAX_STACK_SIZE/"
                    + "CONSUMABLE) did not resolve on " + environment.describe()
                    + " — a mapping break; CT8c stack sizes and drink durations are unavailable.");
        }
        return adapter;
    }

    private Ct8cConsumableAdapter() {
        boolean resolved = false;
        Object maxStack = null;
        Object consumable = null;
        Object drink = null;
        Method set = null;
        Method remove = null;
        Method has = null;

        try {
            Class<?> nmsDataComponents = Class.forName("net.minecraft.core.component.DataComponents");
            if (fieldPresent(nmsDataComponents, "MAX_STACK_SIZE") && fieldPresent(nmsDataComponents, "CONSUMABLE")) {
                Class<?> nmsItemStack = Class.forName("net.minecraft.world.item.ItemStack");
                Class<?> nmsComponentType = Class.forName("net.minecraft.core.component.DataComponentType");
                set = findItemStackBinary(nmsItemStack, "set", nmsComponentType);
                remove = findItemStackUnary(nmsItemStack, "remove", nmsComponentType);
                has = findItemStackUnary(nmsItemStack, "has", nmsComponentType);

                maxStack = nmsDataComponents.getField("MAX_STACK_SIZE").get(null);
                consumable = nmsDataComponents.getField("CONSUMABLE").get(null);
                drink = buildDrinkConsumable();

                resolved = set != null && remove != null && has != null
                        && maxStack != null && consumable != null && drink != null;
            }
        } catch (Throwable absent) {
            resolved = false; // No component model — stay unsupported, all fields null.
        }

        if (!resolved) {
            maxStack = null;
            consumable = null;
            drink = null;
            set = null;
            remove = null;
            has = null;
        }

        this.supported = resolved;
        this.maxStackType = maxStack;
        this.consumableType = consumable;
        this.drinkConsumable = drink;
        this.nmsItemStackSet = set;
        this.nmsItemStackRemove = remove;
        this.nmsItemStackHas = has;
    }

    /** Whether this server can drive the CT8c consumable components (1.20.5+). */
    public boolean supported() {
        return supported;
    }

    public @NotNull String describe() {
        return supported ? "MAX_STACK_SIZE + CONSUMABLE via nms" : "(none — <1.20.5 no-op)";
    }

    /* ------------------------------------------------------------------ */
    /*  Apply / strip                                                      */
    /* ------------------------------------------------------------------ */

    /** Sets the CT8c max stack size on {@code stack}; true when the stack was modified. */
    public boolean stampStackSize(@Nullable ItemStack stack, int size) {
        if (!supported || stack == null || stack.getType() == Material.AIR || maxStackType == null) {
            return false;
        }
        return nmsSet(stack, maxStackType, Integer.valueOf(size));
    }

    /** Sets the 20-tick drink duration on {@code stack}; true when the stack was modified. */
    public boolean stampDrinkDuration(@Nullable ItemStack stack) {
        if (!supported || stack == null || stack.getType() == Material.AIR
                || consumableType == null || drinkConsumable == null) {
            return false;
        }
        return nmsSet(stack, consumableType, drinkConsumable);
    }

    /** Removes any CT8c components this driver may have applied; true when the stack was modified. */
    public boolean strip(@Nullable ItemStack stack) {
        if (!supported || stack == null || stack.getType() == Material.AIR) {
            return false;
        }
        boolean changed = false;
        if (maxStackType != null) {
            changed |= nmsRemove(stack, maxStackType);
        }
        if (consumableType != null) {
            changed |= nmsRemove(stack, consumableType);
        }
        return changed;
    }

    /* ------------------------------------------------------------------ */
    /*  Material classification (pure, unit-pinned)                        */
    /* ------------------------------------------------------------------ */

    /** A drinkable potion — the {@code POTION} material only (splash/lingering stay 1, spec §2.8). */
    public static boolean isDrinkablePotion(@Nullable Material material) {
        return material != null && material.name().equals("POTION");
    }

    /** A snowball — stacks to 64 in CT8c (spec §2.10). */
    public static boolean isSnowball(@Nullable Material material) {
        return material != null && material.name().equals("SNOWBALL");
    }

    /**
     * A "drink" for the 20-tick duration (spec §2.7): potions, milk, honey. Stews
     * keep their own eat animation (see the class note); the duration mechanic is
     * scoped to the genuine drinks the {@code DRINK} animation fits.
     */
    public static boolean isDrink(@Nullable Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        return name.equals("POTION") || name.equals("MILK_BUCKET") || name.equals("HONEY_BOTTLE");
    }

    /* ------------------------------------------------------------------ */
    /*  NMS component construction (verified pattern)                      */
    /* ------------------------------------------------------------------ */

    /**
     * Builds a {@code Consumable} with {@code consume_seconds = 1.0} (20 ticks)
     * and the {@code DRINK} animation via the same builder path
     * {@code SwordBlockAdapter} uses for its BLOCK consumable.
     */
    private static @Nullable Object buildDrinkConsumable() {
        try {
            Class<?> nmsUseAnim = Class.forName("net.minecraft.world.item.ItemUseAnimation");
            Object drink = nmsUseAnim.getField("DRINK").get(null);
            Class<?> nmsConsumable = Class.forName("net.minecraft.world.item.component.Consumable");
            Object builder = nmsConsumable.getMethod("builder").invoke(null);
            builder = builder.getClass().getMethod("consumeSeconds", float.class).invoke(builder, 1.0f);
            builder = builder.getClass().getMethod("animation", nmsUseAnim).invoke(builder, drink);
            return builder.getClass().getMethod("build").invoke(builder);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Reflective set/remove plumbing                                     */
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
}
