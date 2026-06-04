package me.vexmc.mental.module.knockback;

import me.vexmc.mental.platform.Attributes;
import me.vexmc.mental.platform.Enchantments;
import org.bukkit.Material;
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

    /**
     * Captures a knockback victim. Player motion comes from the
     * {@link VictimMotion} ledger — the legacy residual model — because the
     * server's own view of player motion is reverted or stale on every
     * supported version. Mobs are server-simulated, so their live velocity
     * is already the legacy-correct input.
     */
    @SuppressWarnings("deprecation") // Entity#isOnGround: the client-reported value drives the residual decay
    public static @NotNull EntityState captureVictim(
            @NotNull LivingEntity victim, @NotNull VictimMotion ledger, long nowNanos) {
        if (!(victim instanceof Player player)) {
            return capture(victim);
        }
        VictimMotion.Motion motion = ledger.current(
                player.getUniqueId(),
                nowNanos,
                player.isOnGround(),
                Attributes.valueOr(player, Attributes.gravity(), VictimMotion.DEFAULT_GRAVITY));
        return new EntityState(
                player.getLocation().getX(),
                player.getLocation().getZ(),
                player.getLocation().getYaw(),
                motion.vx(),
                motion.vy(),
                motion.vz(),
                player.isSprinting(),
                heldKnockbackLevel(player),
                clampedResistance(player));
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
        return weapon.getEnchantmentLevel(Enchantments.knockback());
    }

    private static double clampedResistance(LivingEntity entity) {
        double value = Attributes.valueOr(entity, Attributes.knockbackResistance(), 0.0);
        return Math.max(0.0, Math.min(1.0, value));
    }
}
