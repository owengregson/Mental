package me.vexmc.mental.v5.platform;

import java.lang.reflect.Method;
import java.util.function.Consumer;
import me.vexmc.mental.common.platform.ServerEnvironment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The minimal boot-probed NMS/API adapter for the damage family (spec §9; the
 * 4B slice of the Phase-5 {@code PlatformProfile}). Every version-gated
 * capability is resolved ONCE at boot from the running server, cached, and read
 * on the hot path as a plain field access. An {@code OptionalSince} capability
 * that is simply absent on an older server is a typed absence with a declared
 * fallback (quiet, expected); a capability that <em>should</em> be present for
 * the running version but does not resolve is a mapping break, logged loudly
 * once (B10) before degrading to the fallback — the invariant is never to throw
 * into a hit.
 *
 * <p>4B capabilities: the {@code max_damage} item component (the custom
 * durability ceiling, Minecraft 1.20.5+) and — added by the sword-blocking
 * slice — the block-component adapter. 4C added the weapon-tooltip adapter; 4D
 * added the {@code ATTACK_RANGE} item-component adapter (the loadout era-reach
 * weapon lever, 1.21.5+).</p>
 */
public final class PlatformProbe {

    /** The version the {@code max_damage} component (the {@code Damageable} accessors) first appears. */
    private static final int MAX_DAMAGE_SINCE_MAJOR = 1;
    private static final int MAX_DAMAGE_SINCE_MINOR = 20;
    private static final int MAX_DAMAGE_SINCE_PATCH = 5;

    private final @Nullable Method hasMaxDamage;
    private final @Nullable Method getMaxDamage;
    private final @NotNull SwordBlockAdapter swordBlock;
    private final @NotNull WeaponTooltipAdapter weaponTooltip;
    private final @NotNull AttackRangeAdapter attackRange;

    private PlatformProbe(
            @Nullable Method hasMaxDamage, @Nullable Method getMaxDamage,
            @NotNull SwordBlockAdapter swordBlock, @NotNull WeaponTooltipAdapter weaponTooltip,
            @NotNull AttackRangeAdapter attackRange) {
        this.hasMaxDamage = hasMaxDamage;
        this.getMaxDamage = getMaxDamage;
        this.swordBlock = swordBlock;
        this.weaponTooltip = weaponTooltip;
        this.attackRange = attackRange;
    }

    /**
     * Boot-probes every 4B/4C capability against the running server, loud-logging
     * a mapping break (a capability the version should have but does not resolve).
     */
    public static @NotNull PlatformProbe probe(
            @NotNull ServerEnvironment environment, @NotNull Consumer<String> log) {
        boolean maxDamageExpected = environment.isAtLeast(
                MAX_DAMAGE_SINCE_MAJOR, MAX_DAMAGE_SINCE_MINOR, MAX_DAMAGE_SINCE_PATCH);
        Method has = probeDamageable("hasMaxDamage");
        Method get = probeDamageable("getMaxDamage");
        if (maxDamageExpected && (has == null || get == null)) {
            log.accept("platform-probe: the max_damage component accessors (Damageable#hasMaxDamage/"
                    + "getMaxDamage) did not resolve on " + environment.describe()
                    + " — a mapping break; tool durability falls back to the material max.");
        }
        return new PlatformProbe(has, get,
                SwordBlockAdapter.probe(environment, log),
                WeaponTooltipAdapter.probe(environment, log),
                AttackRangeAdapter.probe(environment, log));
    }

    /** The block-component adapter (tier detection + apply/strip/block-state). */
    public @NotNull SwordBlockAdapter swordBlock() {
        return swordBlock;
    }

    /** The weapon-tooltip adapter (attack-cooldown attack-speed line strip). */
    public @NotNull WeaponTooltipAdapter weaponTooltip() {
        return weaponTooltip;
    }

    /** The era {@code ATTACK_RANGE} item-component adapter (the loadout hitbox weapon lever, 1.21.5+). */
    public @NotNull AttackRangeAdapter attackRange() {
        return attackRange;
    }

    /**
     * The item's effective maximum durability: the custom {@code max_damage}
     * component when the meta carries one (1.20.5+), else the material max. Reads
     * the component through {@code meta} (already resolved by the caller). A
     * reflective slip after a successful boot probe degrades to the material max.
     */
    public int effectiveMaxDurability(@NotNull ItemStack weapon, @NotNull Damageable meta) {
        int materialMax = weapon.getType().getMaxDurability();
        if (hasMaxDamage == null || getMaxDamage == null) {
            return materialMax;
        }
        try {
            if (Boolean.TRUE.equals(hasMaxDamage.invoke(meta))) {
                int custom = ((Number) getMaxDamage.invoke(meta)).intValue();
                return custom > 0 ? custom : materialMax;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Probed present at boot; fall back to the material max on any slip.
        }
        return materialMax;
    }

    /** Reflect a no-arg {@code Damageable} meta method (1.20.5+); {@code null} on an older platform. */
    private static @Nullable Method probeDamageable(@NotNull String name) {
        try {
            return Damageable.class.getMethod(name);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }
}
