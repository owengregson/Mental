package me.vexmc.mental.v5.feature.damage;

import java.lang.reflect.Method;
import me.vexmc.mental.kernel.math.DamageTables;
import me.vexmc.mental.kernel.model.HitContext;
import me.vexmc.mental.platform.Attributes;
import me.vexmc.mental.platform.CritPosture;
import me.vexmc.mental.platform.EffectiveMaterial;
import me.vexmc.mental.platform.Enchantments;
import me.vexmc.mental.platform.LegacyMaterialNames;
import me.vexmc.mental.platform.PotionEffects;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * The fast-path damage AMOUNT composer (spec §4.6; the retired
 * {@code hitreg.DamageCalculator}/{@code HitApplier} amount seam on the v5
 * kernel math). The netty fast path cancels the vanilla attack packet and calls
 * {@code victim.damage(amount, attacker)} directly on the victim's owning thread;
 * because {@code Player#attack} never runs, the 1.9 attack-charge scaling is
 * never consulted — the era "full damage every swing" holds structurally, no
 * per-hit charge reset needed.
 *
 * <p>The composition is the fixed legacy order — <strong>weapon base (× era
 * strength − era weakness) → crit ×1.5 → + Sharpness(1.25·level)</strong>, crit
 * never multiplying the Sharpness additive — delegated entirely to the pure
 * kernel {@link DamageTables}. When OCM shapes damage for the attacker (the
 * single {@link DamageOwnership} verdict), the shaper instead hands
 * <em>vanilla-shaped</em> damage (live attribute base, 1.9 crit with sprint
 * exclusion, 1.9 Sharpness) so OCM decomposes and re-composes its configured
 * values losslessly (era pin: sharpness-5 diamond = 14.25, never 17.5).</p>
 */
public final class DamageShaper {

    /** Strength/Weakness effect types, resolved once (registry key on 1.20.5+, legacy name below). */
    private static final PotionEffectType STRENGTH = resolveEffect("strength", "INCREASE_DAMAGE");
    private static final PotionEffectType WEAKNESS = resolveEffect("weakness", "WEAKNESS");

    private final DamageOwnership ownership;

    public DamageShaper(DamageOwnership ownership) {
        this.ownership = ownership;
    }

    /** The shared crit/tool-damage verdict source — identical instance to the crit fallback (mandate §4.6). */
    public DamageOwnership ownership() {
        return ownership;
    }

    /* ------------------------------------------------------------------ */
    /*  Pure composition (unit-pinned)                                     */
    /* ------------------------------------------------------------------ */

    /**
     * The Mental-owned legacy composition: {@code eraPotionBase(weaponBase) →
     * (×1.5 if crit) → + 1.25·sharpnessLevel}, clamped ≥ 0. {@code weaponBase} is
     * already the pure weapon base (legacy tool table or recovered attribute).
     */
    public static double composeLegacy(
            double weaponBase, int strengthAmp, int weaknessAmp,
            boolean oldPotionValues, boolean critical, int sharpnessLevel) {
        double damage = oldPotionValues
                ? DamageTables.eraPotionBase(weaponBase, strengthAmp, weaknessAmp)
                : weaponBase;
        if (critical) {
            damage *= DamageTables.critMultiplier();
        }
        return Math.max(0.0, damage + DamageTables.sharpnessBonus(sharpnessLevel));
    }

    /**
     * The vanilla-shaped composition handed to OCM: the live attribute base, the
     * 1.9 crit rule (sprinting excludes), and the 1.9 Sharpness additive. Never
     * legacy-composed and never era-potion-shaped — OCM re-composes its values.
     */
    public static double composeVanillaShape(
            double attributeBase, boolean critical, boolean sprinting, int sharpnessLevel) {
        double damage = attributeBase;
        if (critical && !sprinting) {
            damage *= DamageTables.critMultiplier();
        }
        return Math.max(0.0, damage + DamageTables.vanillaSharpnessBonus(sharpnessLevel));
    }

    /**
     * The era crit posture (shared with the crit fallback): the attacker is
     * falling, off the ground, not climbing, not in water, not blinded, and not
     * riding. Sprinting does NOT exclude a legacy crit (the 1.9 exclusion is
     * post-era). Bukkit-reading; owning-thread only.
     */
    public static boolean isLegacyCritical(Player attacker) {
        // CritPosture.climbing / .inWater, not Player#isClimbing()/isInWater(): those Bukkit accessors floor
        // at 1.17 and 1.16 respectively (isClimbing absent on EVERY legacy revision), so a direct call
        // throws NoSuchMethodError there when a crit feature is enabled. The resolver uses the modern
        // methods where present (byte-identical on 1.17+) and a feet-block read below. hasPotionEffect is
        // present across the whole range (1.9.4+), so it stays a direct call.
        return attacker.getFallDistance() > 0.0f
                && !attacker.isOnGround()
                && !CritPosture.climbing(attacker)
                && !CritPosture.inWater(attacker)
                && !attacker.hasPotionEffect(PotionEffectType.BLINDNESS)
                && attacker.getVehicle() == null;
    }

    /* ------------------------------------------------------------------ */
    /*  Bukkit-reading orchestration (owning thread)                       */
    /* ------------------------------------------------------------------ */

    /**
     * Composes the amount for {@code victim.damage(amount, attacker)}. Reads the
     * attacker's live state on the owning thread (region-guarded by the caller);
     * a non-living target degrades to 1.0 (the retired {@code HitApplier} default).
     *
     * @param oldPotionValues whether {@code old-potion-values} is enabled this hit
     * @param legacyToolDamage whether the fast path composes off the legacy tool table
     * @param simulateCrits whether the fast path injects the era crit
     */
    public double compose(
            Player attacker, HitContext context,
            boolean simulateCrits, boolean legacyToolDamage, boolean oldPotionValues) {
        boolean vanillaShape = context != null
                ? ownership.ocmShapesDamage(context)
                : ownership.ocmShapesDamage(attacker.getUniqueId());
        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        int sharpness = sharpnessLevel(weapon);
        boolean critical = simulateCrits && isLegacyCritical(attacker);

        if (vanillaShape) {
            double attributeBase = Attributes.valueOr(attacker, Attributes.attackDamage(), 1.0);
            return composeVanillaShape(attributeBase, critical, attacker.isSprinting(), sharpness);
        }

        int strengthAmp = oldPotionValues ? amplifier(attacker, STRENGTH) : -1;
        int weaknessAmp = oldPotionValues ? amplifier(attacker, WEAKNESS) : -1;
        double weaponBase = legacyToolDamage ? eraToolBase(weapon) : Double.NaN;
        if (Double.isNaN(weaponBase)) {
            // No legacy tool value (hands, hoes, non-tool) or legacy tables off:
            // recover the pure weapon base from the live attribute so the era
            // potion factors land on the bare weapon (never the modern modifiers).
            double attributeBase = Attributes.valueOr(attacker, Attributes.attackDamage(), 1.0);
            weaponBase = oldPotionValues
                    ? DamageTables.recoverPureBase(attributeBase, strengthAmp, weaknessAmp)
                    : attributeBase;
        }
        return composeLegacy(weaponBase, strengthAmp, weaknessAmp, oldPotionValues, critical, sharpness);
    }

    /**
     * The era weapon base for the weapon's effective material, or {@code NaN} when
     * the legacy tool table has no entry (hands, hoes, non-tools). The effective
     * material name is normalized through {@link LegacyMaterialNames#modernize}
     * before it keys the kernel table: on a pre-flattening server {@code
     * Material.name()} returns the OLD constant ({@code WOOD_SWORD}, {@code
     * GOLD_SPADE}, …), which the kernel's modern-named {@code weaponDamage} would
     * otherwise miss (returning null → era damage silently off, the Q1 defect).
     * The kernel stays version-blind; this platform-seam call is the only
     * translation point.
     *
     * <p>Public so the legacy-boot rules-smoke can assert this exact seam headlessly
     * (a {@code WOOD_SWORD} stack must resolve to the {@code WOODEN_SWORD} pin).</p>
     */
    public static double eraToolBase(ItemStack weapon) {
        Material effective = EffectiveMaterial.of(weapon);
        Double base = DamageTables.weaponDamage(LegacyMaterialNames.modernize(effective.name()));
        return base == null ? Double.NaN : base;
    }

    private static int sharpnessLevel(ItemStack weapon) {
        Enchantment sharpness = Enchantments.sharpness();
        if (weapon == null || weapon.getType() == Material.AIR || sharpness == null) {
            return 0;
        }
        return weapon.getEnchantmentLevel(sharpness);
    }

    /** The 0-based amplifier of {@code type} on the attacker, or -1 when absent (or the type is unresolved). */
    private static int amplifier(Player attacker, PotionEffectType type) {
        if (type == null) {
            return -1;
        }
        // PotionEffects.of, not attacker.getPotionEffect(type): the single-effect accessor is absent on
        // 1.9.4 (floors at 1.10.2), where a direct call throws; the resolver scans the active set below it.
        PotionEffect effect = PotionEffects.of(attacker, type);
        return effect == null ? -1 : effect.getAmplifier();
    }

    /**
     * Resolves a potion effect type by its Minecraft registry key (Paper 1.20.5+),
     * falling back to the legacy {@code getByName} spelling. The registry-key
     * accessor is version-gated, so it is reached reflectively; any failure yields
     * a null type, which {@link #amplifier} treats as "effect absent".
     */
    @SuppressWarnings("deprecation") // getByName is the pre-1.20.5 spelling
    private static PotionEffectType resolveEffect(String modernKey, String legacyName) {
        try {
            Method byKey = PotionEffectType.class.getMethod("getByKey", NamespacedKey.class);
            Object resolved = byKey.invoke(null, NamespacedKey.minecraft(modernKey));
            if (resolved instanceof PotionEffectType type) {
                return type;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Fall through to the legacy name accessor below. LinkageError also catches NamespacedKey (the
            // class, and #minecraft) being absent below 1.12 — this resolver runs at class-init (STRENGTH /
            // WEAKNESS), so it must never let a missing modern symbol poison the whole damage path.
        }
        return PotionEffectType.getByName(legacyName);
    }

    /**
     * The resistance effect type (registry key on 1.20.5+, legacy name below) — used by the armour unit.
     * Delegates to {@link #resolveEffect} so the modern registry key is built inside the same
     * LinkageError-guarded path (no static {@code NamespacedKey} field, which would break class-init below
     * 1.12 where the type is absent).
     */
    public static PotionEffectType resistanceType() {
        return resolveEffect("resistance", "DAMAGE_RESISTANCE");
    }
}
