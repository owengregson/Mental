package me.vexmc.mental.module.knockback;

import me.vexmc.mental.platform.Attributes;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * The exact slice of an entity the knockback formula consumes — capturable
 * on the owning thread, then usable from anywhere.
 */
public record EntityState(
        double x,
        double z,
        float yaw,
        double vx,
        double vy,
        double vz,
        boolean sprinting,
        int knockbackEnchantLevel,
        double knockbackResistance) {

    /** Captures live state; must run on the entity's owning thread. */
    public static @NotNull EntityState capture(@NotNull LivingEntity entity) {
        Vector velocity = entity.getVelocity();
        return new EntityState(
                entity.getLocation().getX(),
                entity.getLocation().getZ(),
                entity.getLocation().getYaw(),
                velocity.getX(),
                velocity.getY(),
                velocity.getZ(),
                entity instanceof Player player && player.isSprinting(),
                heldKnockbackLevel(entity),
                clampedResistance(entity));
    }

    private static int heldKnockbackLevel(LivingEntity entity) {
        EntityEquipment equipment = entity.getEquipment();
        if (equipment == null) {
            return 0;
        }
        ItemStack weapon = equipment.getItemInMainHand().getType() == Material.AIR
                ? equipment.getItemInOffHand()
                : equipment.getItemInMainHand();
        if (weapon == null || weapon.getType() == Material.AIR) {
            return 0;
        }
        return weapon.getEnchantmentLevel(Enchantment.KNOCKBACK);
    }

    private static double clampedResistance(LivingEntity entity) {
        double value = Attributes.valueOr(entity, Attributes.knockbackResistance(), 0.0);
        return Math.max(0.0, Math.min(1.0, value));
    }
}
