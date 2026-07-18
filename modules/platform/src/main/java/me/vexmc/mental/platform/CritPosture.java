package me.vexmc.mental.platform;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Cross-version reads for the 1.8 critical-hit posture (the vanilla
 * {@code EntityHuman.attack} crit condition: falling, off-ground, not on a
 * climbable, not in water, not blinded, not riding). Three of those reads use
 * Bukkit accessors that arrived across the range and are absent on the legacy
 * backport's targets:
 *
 * <ul>
 *   <li>{@code LivingEntity#isClimbing()} — Bukkit 1.17 (absent on <em>every</em>
 *       supported legacy revision, 1.9.4–1.16.5);</li>
 *   <li>{@code Entity#isInWater()} — Bukkit 1.16 (absent 1.9.4–1.15.2);</li>
 *   <li>{@code HumanEntity#getAttackCooldown()} — Bukkit 1.15.2 (absent
 *       1.9.4–1.13.2).</li>
 * </ul>
 *
 * <p>Each accessor is chosen ONCE at class load (never a version parse). Where the
 * modern Bukkit method is present it is used verbatim — byte-identical to the
 * pre-backport direct call, so nothing changes on the 1.17+ range. Below it, a
 * version-neutral fallback restores the era intent:</p>
 *
 * <ul>
 *   <li><b>climbing</b> → the block at the entity's feet is an era climbable
 *       (ladder / vine), which is exactly what vanilla's {@code onClimbable}
 *       gate tests;</li>
 *   <li><b>in-water</b> → the block at the entity's feet is water;</li>
 *   <li><b>attack-charge</b> → the NMS {@code getCooledAttackStrength(0.5f)}
 *       (spigot-mapped from 1.13.2) where it resolves, else {@code 1.0f}
 *       (treat the swing as fully charged, so the crit fallback defers to
 *       vanilla's own crit for non-sprint hits and never double-crits — the
 *       safe direction; the pre-1.13 accessor is obfuscated per-revision).</li>
 * </ul>
 *
 * <p>These reads sit on the fast-path / crit-fallback crit condition, which a
 * clientless test player does not traverse; the selection is unit-pinned and the
 * era crit value itself is kernel-pinned.</p>
 */
public final class CritPosture {

    private enum ClimbSource { BUKKIT_METHOD, FEET_BLOCK }

    private enum WaterSource { BUKKIT_METHOD, FEET_BLOCK }

    private enum ChargeSource { BUKKIT_METHOD, NMS_STRENGTH, FULL_CHARGE }

    private static final ClimbSource CLIMB = resolveClimb();
    private static final WaterSource WATER = resolveWater();
    private static final ChargeSource CHARGE = resolveCharge();
    private static final @Nullable Method NMS_ATTACK_STRENGTH =
            CHARGE == ChargeSource.NMS_STRENGTH ? nmsAttackStrengthMethod() : null;

    /** Era climbable blocks — the vanilla {@code onClimbable} set on the pre-1.17 targets. */
    private static final Set<Material> CLIMBABLES = climbables();

    /** Water blocks — including the pre-1.13 {@code STATIONARY_WATER} still-water constant, by name. */
    private static final Set<Material> WATERS = waters();

    private CritPosture() {}

    /** Whether the entity is on a climbable (ladder / vine) — the crit-negating {@code onClimbable} read. */
    public static boolean climbing(@NotNull LivingEntity entity) {
        if (CLIMB == ClimbSource.BUKKIT_METHOD) {
            return entity.isClimbing();
        }
        return CLIMBABLES.contains(feetBlock(entity));
    }

    /** Whether the entity is in water — the crit-negating {@code isInWater} read. */
    public static boolean inWater(@NotNull Entity entity) {
        if (WATER == WaterSource.BUKKIT_METHOD) {
            return entity.isInWater();
        }
        return WATERS.contains(feetBlock(entity));
    }

    /** The player's attack-charge fraction (0..1); the swing is "fully charged" at &gt; 0.9. */
    public static float attackCharge(@NotNull HumanEntity human) {
        switch (CHARGE) {
            case BUKKIT_METHOD:
                return human.getAttackCooldown();
            case NMS_STRENGTH:
                return nmsAttackCharge(human);
            case FULL_CHARGE:
            default:
                return 1.0f;
        }
    }

    /** For the boot report: the three resolved strategies. */
    public static @NotNull String describe() {
        return "climbing=" + CLIMB + " in-water=" + WATER + " attack-charge=" + CHARGE;
    }

    /* ------------------------------------------------------------------ */
    /*  Resolution                                                         */
    /* ------------------------------------------------------------------ */

    private static ClimbSource resolveClimb() {
        return methodPresent(LivingEntity.class, "isClimbing")
                ? ClimbSource.BUKKIT_METHOD : ClimbSource.FEET_BLOCK;
    }

    private static WaterSource resolveWater() {
        return methodPresent(Entity.class, "isInWater")
                ? WaterSource.BUKKIT_METHOD : WaterSource.FEET_BLOCK;
    }

    private static ChargeSource resolveCharge() {
        if (methodPresent(HumanEntity.class, "getAttackCooldown")) {
            return ChargeSource.BUKKIT_METHOD;
        }
        if (nmsAttackStrengthMethod() != null) {
            return ChargeSource.NMS_STRENGTH;
        }
        return ChargeSource.FULL_CHARGE;
    }

    private static float nmsAttackCharge(@NotNull HumanEntity human) {
        try {
            Method getHandle = human.getClass().getMethod("getHandle");
            Object handle = getHandle.invoke(human);
            if (handle != null && NMS_ATTACK_STRENGTH != null) {
                Object value = NMS_ATTACK_STRENGTH.invoke(handle, 0.5f);
                if (value instanceof Number charge) {
                    return charge.floatValue();
                }
            }
        } catch (ReflectiveOperationException absent) {
            // Unknown mapping — fall through to fully-charged (never double-crits).
        }
        return 1.0f;
    }

    /**
     * The spigot-mapped {@code getCooledAttackStrength(float)} on the running server's {@code EntityHuman}
     * handle type — the delegate CraftBukkit's {@code getAttackCooldown()} calls with 0.5f. Spigot named it
     * from 1.13.2; below that it is an obfuscated single-letter method that drifts per revision, so it does
     * not resolve here (the caller then falls to the fully-charged default).
     */
    private static @Nullable Method nmsAttackStrengthMethod() {
        try {
            Class<?> craftHuman = Class.forName(
                    "org.bukkit.craftbukkit." + nmsPackageVersion() + ".entity.CraftHumanEntity");
            Method getHandle = craftHuman.getMethod("getHandle");
            Class<?> handleType = getHandle.getReturnType();
            return handleType.getMethod("getCooledAttackStrength", float.class);
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return null;
        }
    }

    private static Material feetBlock(@NotNull Entity entity) {
        return entity.getLocation().getBlock().getType();
    }

    private static Set<Material> climbables() {
        Set<Material> set = EnumSet.noneOf(Material.class);
        addByName(set, "LADDER");
        addByName(set, "VINE");
        return set;
    }

    private static Set<Material> waters() {
        Set<Material> set = EnumSet.noneOf(Material.class);
        addByName(set, "WATER");
        addByName(set, "STATIONARY_WATER"); // pre-1.13 still-water constant
        return set;
    }

    private static void addByName(@NotNull Set<Material> set, @NotNull String name) {
        try {
            set.add(Material.valueOf(name));
        } catch (IllegalArgumentException absent) {
            // Constant not on this server's Material enum — ignore (flattening drift).
        }
    }

    private static boolean methodPresent(@NotNull Class<?> owner, @NotNull String name, Class<?>... params) {
        try {
            owner.getMethod(name, params);
            return true;
        } catch (NoSuchMethodException absent) {
            return false;
        }
    }

    private static @NotNull String nmsPackageVersion() {
        String pkg = org.bukkit.Bukkit.getServer().getClass().getPackage().getName();
        int lastDot = pkg.lastIndexOf('.');
        return lastDot >= 0 ? pkg.substring(lastDot + 1) : pkg;
    }
}
