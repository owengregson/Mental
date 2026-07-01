package me.vexmc.mental.module.damage;

import java.lang.reflect.Method;
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

    /**
     * {@code Damageable.hasMaxDamage()}/{@code getMaxDamage()} — the custom {@code max_damage} component
     * (Minecraft 1.20.5+, absent on the 1.17.1 compile floor), reflected once. {@code null} on an older
     * platform, where the effective max is simply the material max.
     */
    private static final Method HAS_MAX_DAMAGE = probe("hasMaxDamage");
    private static final Method GET_MAX_DAMAGE = probe("getMaxDamage");

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
        // Break against the item's EFFECTIVE max: the custom max_damage component when the item carries one
        // (1.20.5+), else the material max. A display-swapped diamond-in-disguise (e.g. a heroic gold sword
        // with a diamond max_damage of 1561) then wears like diamond, not gold's 32 — the pre-fix bug.
        int max = effectiveMax(weapon, meta);
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

    /**
     * The item's effective maximum durability: the custom {@code max_damage} component when the meta
     * carries one (1.20.5+, read reflectively), else the material max. Reads the component through
     * {@code meta} (which the caller already resolved), so no second meta fetch.
     */
    private static int effectiveMax(@NotNull ItemStack weapon, @NotNull Damageable meta) {
        int materialMax = weapon.getType().getMaxDurability();
        if (HAS_MAX_DAMAGE == null || GET_MAX_DAMAGE == null) {
            return materialMax;
        }
        try {
            if (Boolean.TRUE.equals(HAS_MAX_DAMAGE.invoke(meta))) {
                return effectiveMax(materialMax, true, ((Number) GET_MAX_DAMAGE.invoke(meta)).intValue());
            }
        } catch (ReflectiveOperationException ignored) {
            // probed present at class load; fall back to the material max on any reflective slip
        }
        return materialMax;
    }

    /**
     * Pure choice of effective max: the custom component max when present and positive, else the material
     * max. Unit-pinned — {@code (32, true, 1561) → 1561}; {@code (32, false, …) → 32}.
     */
    static int effectiveMax(int materialMax, boolean hasCustomMax, int customMax) {
        return hasCustomMax && customMax > 0 ? customMax : materialMax;
    }

    /** Reflect a no-arg {@code Damageable} method (1.20.5+); {@code null} on an older platform. */
    private static Method probe(@NotNull String name) {
        try {
            return Damageable.class.getMethod(name);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }
}
