package me.vexmc.mental.v5.feature.damage;

import java.lang.reflect.Field;
import me.vexmc.mental.platform.CleavingRegistrar;
import me.vexmc.mental.platform.CritPosture;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Combat Test 8c weapon damage (spec §2.2). Overwrites the melee BASE with the
 * CT8c table value ({@link DamageShaper#ct8cToolBase}) composed through the CT8c
 * ordering ({@link DamageShaper#composeCt8c}): the weapon base carries the CT8c
 * ±20%/level Strength/Weakness (MULTIPLY_TOTAL), then Sharpness + Cleaving +
 * Impaling fold in as enchant damage. The flat ×1.5 crit is NOT applied here —
 * {@code Ct8cCritsUnit} multiplies the finished BASE at a later priority, so the
 * enchant is inside the crit exactly as spec §2.3 requires, whichever units are on.
 *
 * <p><b>No cross-feature snapshot reads.</b> Cleaving folds in via the platform
 * {@link CleavingRegistrar} handle, which is installed ONLY when the {@code
 * cleaving} feature is enabled — so a disabled Cleaving yields a level of 0
 * naturally, no {@code enabled(CLEAVING)} check needed. Strength/Weakness are the
 * CT8c ±20% because ct8c-damage recomputes the base from the table (discarding
 * the attribute's vanilla Strength contribution), so it owns the melee
 * Strength/Weakness fold; {@code Ct8cPotionsUnit} must not also substitute the
 * attack-damage modifier for melee (it would double-apply) — it owns instant
 * health/damage and tipped arrows. Impaling now reaches ALL wet victims
 * (spec §2.9): the wet predicate is this unit's Bukkit read.</p>
 *
 * <p>Zero-touch: assembled only when enabled; touches only player melee.</p>
 */
public final class Ct8cDamageUnit implements FeatureUnit, Listener {

    /** The vanilla Impaling per-level bonus is 2.5; CT8c widens only its SCOPE (all wet victims, spec §2.9). */
    private static final @Nullable Enchantment IMPALING = resolveImpaling();

    @Override
    public Feature descriptor() {
        return Feature.CT8C_DAMAGE;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        scope.listen(this);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    @SuppressWarnings("deprecation") // the granular BASE modifier is the only pre-armour amount hook Bukkit exposes
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || !(event.getDamager() instanceof Player attacker)
                || !(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        double composed = DamageShaper.composeCt8c(
                DamageShaper.ct8cToolBase(weapon),
                DamageShaper.strengthAmplifier(attacker),
                DamageShaper.weaknessAmplifier(attacker),
                false, // crit is Ct8cCritsUnit's ×1.5 at a later priority — enchant stays inside it
                DamageShaper.sharpnessLevelOf(weapon),
                cleavingLevel(weapon),
                impalingBonus(weapon, victim));
        event.setDamage(DamageModifier.BASE, composed);
    }

    /** The weapon's Cleaving level via the platform handle — 0 when Cleaving is off/unresolved (spec §5 gap). */
    private static int cleavingLevel(ItemStack weapon) {
        return CleavingRegistrar.handle().map(handle -> handle.levelOf(weapon)).orElse(0);
    }

    /**
     * The CT8c Impaling bonus for this hit: {@code 2.5×level} when the weapon
     * carries Impaling AND the victim is wet, else 0. CT8c's only change to
     * Impaling is the scope — vanilla restricts the bonus to aquatic mobs, CT8c
     * applies it to every victim in water or rain (spec §2.9).
     */
    private static double impalingBonus(ItemStack weapon, LivingEntity victim) {
        if (IMPALING == null || weapon == null) {
            return 0.0;
        }
        int level = weapon.getEnchantmentLevel(IMPALING);
        if (level <= 0 || !isWet(victim)) {
            return 0.0;
        }
        return DamageShaper.impalingBonus(level);
    }

    /**
     * Whether the victim counts as "in water or rain" for Impaling. Water is the
     * {@link CritPosture#inWater} seam (its feet-block fallback carries the
     * pre-1.16 legacy tier). Rain is a storm with the victim exposed to the sky —
     * an approximation of vanilla's {@code isInRain} (it also gates on the biome's
     * precipitation type, cross-version-fiddly and a rare PvP edge); the water
     * path is the common, robust one.
     */
    private static boolean isWet(LivingEntity victim) {
        if (CritPosture.inWater(victim)) {
            return true;
        }
        if (!victim.getWorld().hasStorm()) {
            return false;
        }
        return victim.getWorld().getHighestBlockYAt(victim.getLocation()) <= victim.getLocation().getBlockY();
    }

    /** The Impaling enchant, resolved once by static-field name (absent below 1.13 — no trident) → no Impaling fold. */
    private static @Nullable Enchantment resolveImpaling() {
        try {
            Field field = Enchantment.class.getField("IMPALING");
            Object value = field.get(null);
            return value instanceof Enchantment enchantment ? enchantment : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
            return null;
        }
    }
}
