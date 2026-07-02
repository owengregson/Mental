package me.vexmc.mental.v5.feature.damage;

import java.util.concurrent.ThreadLocalRandom;
import me.vexmc.mental.kernel.math.ToolDurabilityMath;
import me.vexmc.mental.platform.Enchantments;
import me.vexmc.mental.v5.platform.PlatformProfile;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.jetbrains.annotations.NotNull;

/**
 * Wears an attacker's main-hand weapon by one hit (the retired
 * {@code module.damage.WeaponDurability} on the v5 seams) — the durability
 * vanilla's {@code Player#attack} would have applied, which the fast path's
 * cancelled attack packet swallowed. A melee weapon loses 1 durability per entity
 * hit (era-unchanged for the attack case), modified by the generic tool/weapon
 * Unbreaking skip ({@code 1/(level+1)} damage chance — kernel
 * {@link ToolDurabilityMath}, NOT the armour 0.6 branch). The break threshold is
 * the item's EFFECTIVE max (the {@code max_damage} component via {@link PlatformProfile}
 * when present, so a diamond-in-disguise wears like diamond, else the material max).
 *
 * <p>Must run on the attacker's owning region thread; it re-reads the main-hand
 * item so a hop between the hit and this call cannot wear a stale/swapped item.</p>
 */
public final class ToolWear {

    private final PlatformProfile platform;

    public ToolWear(PlatformProfile platform) {
        this.platform = platform;
    }

    /** Wears the attacker's main-hand weapon by one hit; no-op when the hand holds no durable item. */
    public void applyOneHit(@NotNull Player attacker) {
        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (weapon == null || weapon.getType() == Material.AIR
                || weapon.getType().getMaxDurability() <= 0
                || !(weapon.getItemMeta() instanceof Damageable meta)) {
            return; // no durable weapon in hand (fist, block, unbreakable)
        }

        Enchantment unbreaking = Enchantments.unbreaking();
        int level = unbreaking == null ? 0 : weapon.getEnchantmentLevel(unbreaking);
        int roll = ThreadLocalRandom.current().nextInt(level + 1);
        if (!ToolDurabilityMath.damagesThisHit(level, roll)) {
            return; // Unbreaking absorbed this hit's wear
        }

        int newDamage = meta.getDamage() + 1;
        int max = platform.effectiveMaxDurability(weapon, meta);
        if (newDamage >= max) {
            breakWeapon(attacker, weapon, meta);
            return;
        }
        meta.setDamage(newDamage);
        weapon.setItemMeta(meta);
        attacker.getInventory().setItemInMainHand(weapon);
    }

    /** Vanilla break: consume one from the stack (the slot empties for size-1) and play the break sound. */
    private static void breakWeapon(
            @NotNull Player attacker, @NotNull ItemStack weapon, @NotNull Damageable meta) {
        int remaining = weapon.getAmount() - 1;
        if (remaining <= 0) {
            attacker.getInventory().setItemInMainHand(null);
        } else {
            weapon.setAmount(remaining);
            meta.setDamage(0); // the next item in the stack starts pristine
            weapon.setItemMeta(meta);
            attacker.getInventory().setItemInMainHand(weapon);
        }
        attacker.playSound(attacker.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
    }
}
