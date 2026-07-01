package me.vexmc.mental.module.hitreg;

import java.lang.reflect.Method;
import me.vexmc.mental.platform.Attributes;
import me.vexmc.mental.platform.EffectiveMaterial;
import me.vexmc.mental.platform.Enchantments;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 1.7.10 damage for the fast path, in the 1.7.10 order: weapon damage,
 * times 1.5 in a critical posture, <em>then</em> the Sharpness bonus —
 * crits never multiplied enchantment damage before 1.9. Armor,
 * invulnerability, knockback and feedback all still flow through the
 * vanilla hurt chain at the damage call.
 *
 * <p>The 1.9 combat update also rebalanced every tool tier (swords lost a
 * point, axes became heavy weapons); {@code legacyToolDamage} restores the
 * pre-1.9 tables — sword {@code 4+tier}, axe {@code 3+tier}, pickaxe
 * {@code 2+tier}, shovel {@code 1+tier}, plus the 1.0 base hand damage.</p>
 *
 * <p>When OldCombatMechanics governs damage for the attacker
 * ({@code vanillaShape}), the composition switches to exactly what vanilla
 * would produce at full charge — attribute base, the 1.9 crit rules
 * (sprinting excludes), the 1.9 Sharpness formula — because OCM's damage
 * machinery <em>decomposes</em> the event back into those components before
 * replacing them with its configured values. Feeding it legacy-composed
 * damage would double-apply sharpness and crits; feeding it vanilla-shaped
 * damage makes OCM the single source of truth for the hit, which is the
 * point of yielding.</p>
 *
 * <p>The 1.9 combat update also weakened Strength and strengthened Weakness:
 * era Strength was a <em>multiplicative</em> bonus on the weapon base
 * ({@code factor = 1 + 2.5×(amp+1)} — Strength I ×3.5, Strength II ×6.0,
 * MULTIPLY_TOTAL [pe.java:17]) versus 1.9's flat {@code +3×(amp+1)} ADD; era
 * Weakness subtracted {@code 2.0×(amp+1)} [pe.java:30 / rv.java:28] versus
 * 1.9's {@code 4.0×(amp+1)}. When {@code oldPotionValues} is set these era
 * values are applied to the <em>weapon base</em> — before the crit ×1.5 and
 * before the Sharpness additive [wn.java:761-764]: {@code eraBase =
 * (weaponBase × strengthFactor) − weaknessReduction}, clamped ≥ 0. This is the
 * fast-path melee scope only; {@code vanillaShape} (the OCM handoff) never
 * applies era potion values.</p>
 */
public final class DamageCalculator {

    private DamageCalculator() {}

    /**
     * Reflected {@code PotionEffectType.getByKey(NamespacedKey)} (Paper 1.20.5+),
     * mirroring {@code GoldenAppleModule}. {@code null} on older builds where the
     * {@code getByName} fallback resolves Strength/Weakness by their legacy names.
     */
    private static final @Nullable Method GET_BY_KEY;

    /** Cached Strength/Weakness types, resolved once at class load (may be null). */
    private static final @Nullable PotionEffectType STRENGTH;
    private static final @Nullable PotionEffectType WEAKNESS;

    static {
        Method m = null;
        try {
            m = PotionEffectType.class.getMethod("getByKey", NamespacedKey.class);
        } catch (NoSuchMethodException ignored) {
            // Pre-1.20.5 Paper — getByName fallback.
        }
        GET_BY_KEY = m;
        STRENGTH = resolveType("strength", "INCREASE_DAMAGE");
        WEAKNESS = resolveType("weakness", "WEAKNESS");
    }

    public static double calculate(
            @NotNull Player attacker,
            @NotNull LivingEntity target,
            boolean simulateCrits,
            boolean legacyToolDamage,
            boolean vanillaShape,
            boolean oldPotionValues) {
        ItemStack weapon = attacker.getInventory().getItemInMainHand();

        if (vanillaShape) {
            // OCM owns the damage model here — hand it vanilla-shaped damage and
            // leave Strength/Weakness entirely to vanilla/OCM (no era values).
            double damage = Attributes.valueOr(attacker, Attributes.attackDamage(), 1.0);
            if (simulateCrits && isCritical(attacker) && !attacker.isSprinting()) {
                damage *= 1.5;
            }
            return Math.max(0.0, damage + vanillaEnchantmentBonus(weapon));
        }

        // The legacy table keys off the weapon's EFFECTIVE material (the neutral combat:effective_material
        // marker when present, else its own type), so a display-swapped "diamond in disguise" (e.g. a heroic
        // gold sword marked DIAMOND_SWORD) gets diamond-era damage instead of the gold display value. Unmarked
        // items resolve to their own type — a pure no-op. Gated on legacyToolDamage: Mental owns the era.
        Double legacy = legacyToolDamage && weapon != null
                ? legacyAttackDamage(EffectiveMaterial.of(weapon))
                : null;
        double damage = legacy != null
                ? legacy
                : Attributes.valueOr(attacker, Attributes.attackDamage(), 1.0);

        if (oldPotionValues) {
            int strengthAmp = amplifier(attacker, STRENGTH);
            int weaknessAmp = amplifier(attacker, WEAKNESS);
            if (strengthAmp >= 0 || weaknessAmp >= 0) {
                // On the legacy-tool path the base carries no strength bonus, so
                // apply the era factors directly. On the attribute path the value
                // already folds in MODERN strength/weakness; recover the pure base
                // first so the era factors land on the bare weapon.
                double base = legacy != null ? damage : recoverPureBase(damage, strengthAmp, weaknessAmp);
                damage = eraPotionBase(base, strengthAmp, weaknessAmp);
            }
        }

        if (simulateCrits && isCritical(attacker)) {
            damage *= 1.5;
        }
        return Math.max(0.0, damage + enchantmentBonus(weapon));
    }

    /* ------------------------------------------------------------------ */
    /*  Era Strength / Weakness (pre-1.9 damage VALUES)                    */
    /* ------------------------------------------------------------------ */

    /**
     * The era Strength multiplier on the weapon base: {@code 1 + 2.5×(amp+1)}
     * (MULTIPLY_TOTAL [pe.java:17]). Strength I (amp 0) → ×3.5, Strength II
     * (amp 1) → ×6.0. An amplifier below 0 means "no Strength" → ×1.
     */
    static double strengthFactor(int amplifier) {
        return amplifier < 0 ? 1.0 : 1.0 + 2.5 * (amplifier + 1);
    }

    /**
     * The era Weakness reduction on the weapon base: {@code 2.0×(amp+1)}
     * (ADD [pe.java:30 / rv.java:28]). Weakness I (amp 0) → 2.0. An amplifier
     * below 0 means "no Weakness" → 0.
     */
    static double weaknessReduction(int amplifier) {
        return amplifier < 0 ? 0.0 : 2.0 * (amplifier + 1);
    }

    /**
     * Applies the era Strength factor then the era Weakness reduction to a pure
     * weapon base, in the era order [wn.java:761-764], clamped ≥ 0. Both effects
     * are gated by their amplifier ({@code < 0} = absent).
     */
    static double eraPotionBase(double weaponBase, int strengthAmp, int weaknessAmp) {
        double value = weaponBase * strengthFactor(strengthAmp) - weaknessReduction(weaknessAmp);
        return Math.max(0.0, value);
    }

    /**
     * Recovers the pure weapon base from a modern attack-damage attribute value,
     * which already folds in MODERN Strength ({@code +3×(amp+1)}) and Weakness
     * ({@code −4×(amp+1)}). Subtracting the modern Strength and adding back the
     * modern Weakness leaves the bare weapon base so the era factors can replace
     * them. Both effects are gated by their amplifier ({@code < 0} = absent).
     *
     * <p>Limitation: this assumes the attribute carries exactly vanilla's modern
     * potion modifiers and no third-party attribute modifiers for these effects.
     * The dominant fast-path scenario uses the legacy-tool path (where the base is
     * already pure), so this recovery only matters when {@code legacyToolDamage}
     * is off.</p>
     */
    static double recoverPureBase(double attrValue, int strengthAmp, int weaknessAmp) {
        double pure = attrValue;
        if (strengthAmp >= 0) {
            pure -= 3.0 * (strengthAmp + 1);
        }
        if (weaknessAmp >= 0) {
            pure += 4.0 * (weaknessAmp + 1);
        }
        return pure;
    }

    /** The attacker's amplifier for {@code type}, or {@code -1} when absent/unresolvable. */
    private static int amplifier(@NotNull Player attacker, @Nullable PotionEffectType type) {
        if (type == null) {
            return -1;
        }
        PotionEffect effect = attacker.getPotionEffect(type);
        return effect == null ? -1 : effect.getAmplifier();
    }

    /**
     * Resolves a {@link PotionEffectType} cross-version: {@code getByKey} via
     * reflection (Paper 1.20.5+), falling back to {@code getByName} with the
     * legacy Bukkit enum name (Strength is {@code INCREASE_DAMAGE}, Weakness is
     * {@code WEAKNESS}). Mirrors {@code GoldenAppleModule}/{@code ArmourStrengthModule}.
     */
    @SuppressWarnings("deprecation")
    private static @Nullable PotionEffectType resolveType(@NotNull String key, @NotNull String legacyName) {
        if (GET_BY_KEY != null) {
            try {
                Object result = GET_BY_KEY.invoke(null, NamespacedKey.minecraft(key));
                if (result instanceof PotionEffectType type) {
                    return type;
                }
            } catch (ReflectiveOperationException ignored) {
                // Fall through to getByName.
            }
        }
        return PotionEffectType.getByName(legacyName);
    }

    /** Pre-1.9 Sharpness: {@code 1.25 × level} (1.9 changed it to {@code 0.5 × level + 0.5}). */
    static double sharpnessBonus(int level) {
        return level <= 0 ? 0.0 : 1.25 * level;
    }

    /** The 1.9+ Sharpness vanilla composes with: {@code 1 + 0.5 × (level − 1)}. */
    static double vanillaSharpnessBonus(int level) {
        return level <= 0 ? 0.0 : 1.0 + 0.5 * (level - 1);
    }

    /**
     * Pre-1.9 total attack damage (base hand 1.0 included) for the four
     * legacy tool classes, or null for anything whose modern attribute
     * already matches the era (hands, hoes) or postdates it.
     */
    static @Nullable Double legacyAttackDamage(@NotNull Material type) {
        String name = type.name();
        double tool;
        if (name.endsWith("_SWORD")) {
            tool = 4.0;
        } else if (name.endsWith("_PICKAXE")) {
            tool = 2.0;
        } else if (name.endsWith("_AXE")) {
            tool = 3.0;
        } else if (name.endsWith("_SHOVEL")) {
            tool = 1.0;
        } else {
            return null;
        }
        double tier;
        if (name.startsWith("WOODEN_") || name.startsWith("GOLDEN_")) {
            tier = 0.0;
        } else if (name.startsWith("STONE_")) {
            tier = 1.0;
        } else if (name.startsWith("IRON_")) {
            tier = 2.0;
        } else if (name.startsWith("DIAMOND_")) {
            tier = 3.0;
        } else if (name.startsWith("NETHERITE_")) {
            tier = 4.0; // extrapolated: one above diamond, the modern tier pattern
        } else {
            return null;
        }
        return 1.0 + tool + tier;
    }

    /**
     * The 1.7.10 critical posture: falling, off the ground and off a
     * ladder, out of water, sighted, on no mount. Sprinting does NOT
     * exclude a crit — that rule arrived with 1.9.
     *
     * <p>This is the shared predicate used by both the fast-path
     * {@link DamageCalculator} and the fast-path-off
     * {@code CritFallbackModule}; one source of truth for the era
     * precondition set so the two paths can never drift.</p>
     *
     * @param attacker the attacking player
     * @param target   the attack target (unused today — carried for API
     *                 symmetry and to leave room for era target checks)
     */
    @SuppressWarnings("deprecation") // Player#isOnGround: client-reported, matching legacy crit rules
    public static boolean isLegacyCritical(Player attacker, @SuppressWarnings("unused") Entity target) {
        return attacker.getFallDistance() > 0.0f
                && !attacker.isOnGround()
                && !attacker.isClimbing()
                && !attacker.isInWater()
                && !attacker.hasPotionEffect(PotionEffectType.BLINDNESS)
                && attacker.getVehicle() == null;
    }

    /**
     * Internal alias — the fast path calls through the shared predicate so
     * the precondition set has exactly one definition.
     */
    private static boolean isCritical(Player attacker) {
        return isLegacyCritical(attacker, null);
    }

    private static double enchantmentBonus(@Nullable ItemStack weapon) {
        if (weapon == null || weapon.getType() == Material.AIR) {
            return 0.0;
        }
        Enchantment sharpness = Enchantments.sharpness();
        return sharpness == null ? 0.0 : sharpnessBonus(weapon.getEnchantmentLevel(sharpness));
    }

    private static double vanillaEnchantmentBonus(@Nullable ItemStack weapon) {
        if (weapon == null || weapon.getType() == Material.AIR) {
            return 0.0;
        }
        Enchantment sharpness = Enchantments.sharpness();
        return sharpness == null ? 0.0 : vanillaSharpnessBonus(weapon.getEnchantmentLevel(sharpness));
    }
}
