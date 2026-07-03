package me.vexmc.mental.platform;

import java.lang.reflect.Method;
import org.bukkit.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Cross-version absorption-amount accessor.
 *
 * <p>{@code LivingEntity#getAbsorptionAmount()} is a Bukkit API method only from
 * 1.15 (declared on {@code Damageable}); below it — the legacy backport reaches to
 * 1.9.4 — it is absent and a direct call is a {@code NoSuchMethodError} at
 * execution (javap-verified absent on 1.9.4–1.13.2). The NMS accessor
 * {@code EntityLiving.getAbsorptionHearts()} is present on <em>every</em> revision
 * (1.9.4→26.x) and is exactly what CraftBukkit's {@code getAbsorptionAmount()}
 * delegates to ({@code getHandle().getAbsorptionHearts()} with a float→double
 * widen — byte-identical unit), so the fallback returns the same value the modern
 * API would.</p>
 *
 * <p>The accessor is chosen ONCE at class load (never a version parse) and every
 * call takes the resolved path:</p>
 *
 * <ol>
 *   <li>{@code LivingEntity#getAbsorptionAmount()} — modern Bukkit (1.15+);</li>
 *   <li>the NMS {@code EntityLiving.getAbsorptionHearts()} on the entity handle —
 *       the universal fallback (1.9.4+).</li>
 * </ol>
 *
 * <p>If neither resolves, absorption reads 0.0 — a benign value the era defence
 * cascade treats as "no absorption shield".</p>
 */
public final class Absorptions {

    private enum Source { BUKKIT_API, NMS_HEARTS, NONE }

    private static final Source SOURCE = resolve();
    private static final @Nullable Method HEARTS = SOURCE == Source.NMS_HEARTS ? nmsHeartsMethod() : null;

    private Absorptions() {}

    /** The entity's current absorption amount (health points), via the boot-resolved accessor, or 0.0. */
    public static double of(@NotNull LivingEntity entity) {
        switch (SOURCE) {
            case BUKKIT_API:
                return entity.getAbsorptionAmount();
            case NMS_HEARTS:
                return nmsHearts(entity);
            case NONE:
            default:
                return 0.0;
        }
    }

    /** The resolved accessor's name — for the boot report and the chain-selection unit pin. */
    public static @NotNull String describe() {
        return switch (SOURCE) {
            case BUKKIT_API -> "LivingEntity#getAbsorptionAmount()";
            case NMS_HEARTS -> "nms EntityLiving.getAbsorptionHearts()";
            case NONE -> "none (absorption reads 0.0)";
        };
    }

    private static Source resolve() {
        if (methodPresent(LivingEntity.class, "getAbsorptionAmount")) {
            return Source.BUKKIT_API;
        }
        if (nmsHeartsMethod() != null) {
            return Source.NMS_HEARTS;
        }
        return Source.NONE;
    }

    private static double nmsHearts(@NotNull LivingEntity entity) {
        try {
            Method getHandle = entity.getClass().getMethod("getHandle");
            Object handle = getHandle.invoke(entity);
            if (handle != null && HEARTS != null) {
                Object value = HEARTS.invoke(handle);
                if (value instanceof Number measured) {
                    return measured.doubleValue();
                }
            }
        } catch (ReflectiveOperationException absent) {
            // A mapping we do not know degrades to "no absorption" — the benign direction.
        }
        return 0.0;
    }

    private static boolean methodPresent(@NotNull Class<?> owner, @NotNull String name) {
        try {
            owner.getMethod(name);
            return true;
        } catch (NoSuchMethodException absent) {
            return false;
        }
    }

    /**
     * The spigot-mapped {@code getAbsorptionHearts()} method on the running server's {@code EntityLiving}
     * handle type, or {@code null} when the CraftBukkit package or the accessor is not where legacy
     * CraftBukkit put it. Resolved from {@code CraftLivingEntity#getHandle}'s declared return type
     * ({@code EntityLiving}); the method is public there and invoking it on any subclass handle
     * (EntityPlayer, EntityZombie, …) is legal.
     */
    private static @Nullable Method nmsHeartsMethod() {
        try {
            Class<?> craftLiving = Class.forName(
                    "org.bukkit.craftbukkit." + nmsPackageVersion() + ".entity.CraftLivingEntity");
            Method getHandle = craftLiving.getMethod("getHandle");
            Class<?> handleType = getHandle.getReturnType();
            return handleType.getMethod("getAbsorptionHearts");
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return null;
        }
    }

    private static @NotNull String nmsPackageVersion() {
        String pkg = org.bukkit.Bukkit.getServer().getClass().getPackage().getName();
        int lastDot = pkg.lastIndexOf('.');
        return lastDot >= 0 ? pkg.substring(lastDot + 1) : pkg;
    }
}
