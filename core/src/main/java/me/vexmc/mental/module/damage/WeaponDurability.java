package me.vexmc.mental.module.damage;

import java.util.concurrent.ThreadLocalRandom;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.common.debug.DebugLog;
import me.vexmc.mental.platform.Enchantments;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.jetbrains.annotations.NotNull;

/**
 * The Bukkit shell that wears an attacker's main-hand weapon by one hit — the
 * durability vanilla's {@code Player#attack} would have applied, which the fast
 * path's cancelled attack packet swallowed.
 *
 * <p>Lives in its own class so it can import the ItemMeta {@link Damageable}
 * without colliding with the entity {@code Damageable} the {@code HitApplier}
 * uses for the hit target, and so the thin Bukkit shell stays separate from the
 * pure {@link ToolDurabilityMath} (the codebase's "pure math in its own class,
 * Bukkit shells stay thin" idiom).</p>
 *
 * <h2>Era rule</h2>
 * <p>A melee weapon loses 1 durability per entity hit (unchanged 1.7→modern for
 * the attack case; block-break is 2, the ATTACK case is 1), modified by the
 * standard tool/weapon Unbreaking skip ({@code 1/(level+1)} damage chance —
 * {@link ToolDurabilityMath}, NOT the armour 0.6 branch). When the new damage
 * reaches the item's maximum the weapon breaks like vanilla: the stack
 * decrements (the slot empties for a size-1 weapon) and the break sound plays.</p>
 *
 * <h2>Threading</h2>
 * <p>Caller-supplied invariant: this MUST run on the attacker's owning region
 * thread (the {@code HitApplier} wraps it in {@code Scheduling.runOn(attacker)}),
 * because the main-hand read and write are the attacker's entity state. It
 * re-reads the main-hand item itself so a Folia hop between the hit and this task
 * cannot wear a stale or swapped item.</p>
 */
public final class WeaponDurability {

    private WeaponDurability() {}

    /**
     * Wears the attacker's main-hand weapon by one hit, Unbreaking-modified,
     * breaking it like vanilla when exhausted. No-op when the hand holds no
     * durable item. Must be called on the attacker's owning region thread.
     */
    public static void applyOneHit(@NotNull Player attacker, @NotNull DebugLog debug) {
        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (weapon == null || weapon.getType() == Material.AIR
                || weapon.getType().getMaxDurability() <= 0
                || !(weapon.getItemMeta() instanceof Damageable meta)) {
            return; // No durable weapon in hand (fist, block, unbreakable) — nothing to wear.
        }

        Enchantment unbreaking = Enchantments.unbreaking();
        int level = unbreaking == null ? 0 : weapon.getEnchantmentLevel(unbreaking);
        int roll = ThreadLocalRandom.current().nextInt(level + 1);
        if (!ToolDurabilityMath.damagesThisHit(level, roll)) {
            debug.log(DebugCategory.HITREG, () ->
                    "old-tool-durability skipped wear on " + weapon.getType()
                            + " (unbreaking=" + level + ", roll=" + roll + ")");
            return; // Unbreaking absorbed this hit's wear.
        }

        int newDamage = meta.getDamage() + 1;
        int max = weapon.getType().getMaxDurability();
        if (newDamage >= max) {
            breakWeapon(attacker, weapon, meta);
            debug.log(DebugCategory.HITREG, () ->
                    "old-tool-durability broke " + weapon.getType() + " for " + attacker.getName());
            return;
        }
        meta.setDamage(newDamage);
        weapon.setItemMeta(meta);
        attacker.getInventory().setItemInMainHand(weapon);
        debug.log(DebugCategory.HITREG, () ->
                "old-tool-durability +1 on " + weapon.getType()
                        + " (" + newDamage + "/" + max + ") for " + attacker.getName());
    }

    /**
     * Vanilla break: consume one from the stack (the slot empties for a size-1
     * weapon, the usual case; a larger stack keeps a pristine next item) and play
     * the break sound at the attacker.
     */
    private static void breakWeapon(
            @NotNull Player attacker, @NotNull ItemStack weapon, @NotNull Damageable meta) {
        int remaining = weapon.getAmount() - 1;
        if (remaining <= 0) {
            attacker.getInventory().setItemInMainHand(null);
        } else {
            weapon.setAmount(remaining);
            meta.setDamage(0); // The next item in the stack starts pristine.
            weapon.setItemMeta(meta);
            attacker.getInventory().setItemInMainHand(weapon);
        }
        attacker.playSound(attacker.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
    }
}
