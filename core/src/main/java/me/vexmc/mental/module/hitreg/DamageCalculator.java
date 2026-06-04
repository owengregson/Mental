package me.vexmc.mental.module.hitreg;

import me.vexmc.mental.platform.Attributes;
import me.vexmc.mental.platform.Enchantments;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 1.8.x damage for the fast path: attack-damage attribute plus sharpness,
 * times 1.5 in a 1.8 critical posture. Armor, invulnerability, knockback and
 * feedback all still flow through the vanilla hurt chain at the damage call.
 */
public final class DamageCalculator {

    private DamageCalculator() {}

    public static double calculate(@NotNull Player attacker, @NotNull LivingEntity target, boolean simulateCrits) {
        double base = Attributes.valueOr(attacker, Attributes.attackDamage(), 1.0);
        double total = base + enchantmentBonus(attacker.getInventory().getItemInMainHand());
        if (simulateCrits && isCritical(attacker)) {
            total *= 1.5;
        }
        return Math.max(0.0, total);
    }

    /** Vanilla 1.8 sharpness: {@code 1.0 + 0.5 × (level − 1)}. */
    static double sharpnessBonus(int level) {
        return level <= 0 ? 0.0 : 1.0 + 0.5 * (level - 1);
    }

    @SuppressWarnings("deprecation") // Player#isOnGround: client-reported, matching 1.8 crit rules
    private static boolean isCritical(Player attacker) {
        return attacker.getFallDistance() > 0.0f
                && !attacker.isOnGround()
                && !attacker.isInWater()
                && !attacker.hasPotionEffect(PotionEffectType.BLINDNESS)
                && attacker.getVehicle() == null
                && !attacker.isSprinting()
                && attacker.getGameMode() != GameMode.CREATIVE;
    }

    private static double enchantmentBonus(@Nullable ItemStack weapon) {
        if (weapon == null || weapon.getType() == Material.AIR) {
            return 0.0;
        }
        Enchantment sharpness = Enchantments.sharpness();
        return sharpness == null ? 0.0 : sharpnessBonus(weapon.getEnchantmentLevel(sharpness));
    }
}
