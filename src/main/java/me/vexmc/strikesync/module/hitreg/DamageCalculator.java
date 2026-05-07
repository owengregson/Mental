package me.vexmc.strikesync.module.hitreg;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

/**
 * 1.8.x-style damage calculation for the fast hit-registration path.
 *
 * <p>The fast path bypasses vanilla's full {@code Player#attack(Entity)} chain
 * (sweep, attack-cooldown, statistics, weapon durability), so this class
 * computes the base damage that a 1.8 attack would have produced:
 * {@code attack-damage attribute + sharpness bonus}, multiplied by 1.5 if
 * the attacker is in a 1.8-style critical-hit posture.
 *
 * <p>Knockback, armor reduction, invulnerability ticks, and hurt sound/animation
 * are all left to the Bukkit {@code damage(double, Entity)} call site — those
 * paths still go through vanilla.
 */
public final class DamageCalculator {

    private DamageCalculator() {}

    /** Compute the damage value that should be passed to {@code victim.damage(amount, attacker)}. */
    public static double calculate(Player attacker, LivingEntity target, boolean simulateCrits) {
        AttributeInstance attackAttr = attacker.getAttribute(Attribute.ATTACK_DAMAGE);
        double base = attackAttr != null ? attackAttr.getValue() : 1.0D;

        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        double bonus = enchantmentBonus(weapon);
        double total = base + bonus;

        if (simulateCrits && isCritical(attacker)) {
            total *= 1.5D;
        }
        return Math.max(0.0D, total);
    }

    /**
     * 1.8 critical-hit conditions: attacker is falling, not sprinting, not in
     * water, not blind, not in a vehicle, and not in creative mode.
     */
    @SuppressWarnings("deprecation") // Player#isOnGround intentional — see PlayerStateCache.
    private static boolean isCritical(Player attacker) {
        return attacker.getFallDistance() > 0.0F
                && !attacker.isOnGround()
                && !attacker.isInWater()
                && !attacker.hasPotionEffect(PotionEffectType.BLINDNESS)
                && attacker.getVehicle() == null
                && !attacker.isSprinting()
                && attacker.getGameMode() != GameMode.CREATIVE;
    }

    /**
     * Vanilla 1.8 sharpness damage: {@code 1.0 + 0.5 * (level - 1)}. Smite vs
     * undead and Bane of Arthropods vs arthropods are not modelled here —
     * additive enchantment damage for non-axe weapons in 1.8 is dominated by
     * sharpness for PvP; mob-specific bonuses can land in a future pass.
     */
    private static double enchantmentBonus(ItemStack weapon) {
        if (weapon == null || weapon.getType() == Material.AIR) return 0.0D;
        int sharpness = weapon.getEnchantmentLevel(Enchantment.SHARPNESS);
        if (sharpness <= 0) return 0.0D;
        return 1.0D + 0.5D * (sharpness - 1);
    }
}
