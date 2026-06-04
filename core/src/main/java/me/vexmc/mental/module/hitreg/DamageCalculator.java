package me.vexmc.mental.module.hitreg;

import me.vexmc.mental.platform.Attributes;
import me.vexmc.mental.platform.Enchantments;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
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
 */
public final class DamageCalculator {

    private DamageCalculator() {}

    public static double calculate(
            @NotNull Player attacker,
            @NotNull LivingEntity target,
            boolean simulateCrits,
            boolean legacyToolDamage,
            boolean vanillaShape) {
        ItemStack weapon = attacker.getInventory().getItemInMainHand();

        if (vanillaShape) {
            double damage = Attributes.valueOr(attacker, Attributes.attackDamage(), 1.0);
            if (simulateCrits && isCritical(attacker) && !attacker.isSprinting()) {
                damage *= 1.5;
            }
            return Math.max(0.0, damage + vanillaEnchantmentBonus(weapon));
        }

        Double legacy = legacyToolDamage && weapon != null ? legacyAttackDamage(weapon.getType()) : null;
        double damage = legacy != null
                ? legacy
                : Attributes.valueOr(attacker, Attributes.attackDamage(), 1.0);

        if (simulateCrits && isCritical(attacker)) {
            damage *= 1.5;
        }
        return Math.max(0.0, damage + enchantmentBonus(weapon));
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
     */
    @SuppressWarnings("deprecation") // Player#isOnGround: client-reported, matching legacy crit rules
    private static boolean isCritical(Player attacker) {
        return attacker.getFallDistance() > 0.0f
                && !attacker.isOnGround()
                && !attacker.isClimbing()
                && !attacker.isInWater()
                && !attacker.hasPotionEffect(PotionEffectType.BLINDNESS)
                && attacker.getVehicle() == null;
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
