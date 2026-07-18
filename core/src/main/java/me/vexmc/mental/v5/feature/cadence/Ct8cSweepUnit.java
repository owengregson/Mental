package me.vexmc.mental.v5.feature.cadence;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.kernel.math.Ct8cChargeMath;
import me.vexmc.mental.platform.SweepCauses;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Combat Test 8c sweep rules ({@code ct8c-sweep}, CT8c decompile, spec §2.3) — the
 * stricter sweep gate on the vanilla path (the {@code SweepDamageListener}
 * template). A sweep secondary is CANCELLED unless it satisfies BOTH new CT8c
 * conditions: the weapon carries Sweeping Edge (ratio &gt; 0 — plain swords no
 * longer sweep) AND, when {@code charged-attacks} is enabled, the hit was at
 * ≥195% charge ({@code Ct8cChargeMath.sweepAllowed} over the published charge
 * view). A surviving sweep is re-shaped to the CT8c secondary damage
 * {@code 1 + ratio·mainDamage}. The unit also opens axe anvil eligibility for the
 * four book-only enchants (Sweeping Edge / Fire Aspect / Looting / Knockback).
 *
 * <p>The not-crit / not-sprint-knock conditions need no re-check: vanilla's own
 * sweep gate already excludes crits and sprint hits, so an {@code
 * ENTITY_SWEEP_ATTACK} event never fires for them — CT8c only ADDS restrictions.</p>
 *
 * <p>Below 1.11 the {@code ENTITY_SWEEP_ATTACK} cause is absent (a direct constant
 * reference is a sticky {@code NoSuchFieldError} the bus swallows — the 2.4.1
 * GAP-2 finding); {@link SweepCauses} decides once at assemble and the unit is a
 * documented no-op, exactly like {@code SweepUnit}. Zero-touch: default OFF; the
 * gate closes with the scope. It reads the charge view but never the charge
 * ledger, so it is decoupled from {@code charged-attacks}' lifecycle (the ≥195%
 * gate is decided once at assemble; disabled, sweep falls to the enchant gate).</p>
 *
 * <h2>Reported refinement (not a defect)</h2>
 * <p>The secondary damage uses the primary hit's damage captured at its {@code
 * ENTITY_ATTACK} event; the 0.4 sweep knockback (spec §2.3) rides vanilla's own
 * sweep knockback rather than being re-shaped here, and the anvil path merges
 * enchant levels but leaves the repair-cost tuning to vanilla — both noted as
 * refinements, outside the core gate contract.</p>
 */
public final class Ct8cSweepUnit implements FeatureUnit, Listener {

    private final Ct8cChargeView view = Ct8cChargeView.INSTANCE;

    /** The last primary-melee base damage per attacker, feeding the secondary sweep formula. */
    private final Map<UUID, Double> lastMainDamage = new ConcurrentHashMap<>();

    private DamageCause sweepCause;
    private boolean chargeGated;

    @Override
    public Feature descriptor() {
        return Feature.CT8C_SWEEP;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        if (!SweepCauses.present()) {
            logDegrade();
            return;
        }
        this.sweepCause = SweepCauses.sweepCause();
        // The ≥195% charge gate applies only when charged-attacks is enabled; else
        // the sweep is gated on the Sweeping Edge enchant alone (decided once here).
        this.chargeGated = snapshot.enabled(Feature.CHARGED_ATTACKS);
        scope.listen(this);
        scope.task(() -> lastMainDamage::clear);
    }

    /* ------------------------------- the sweep gate ------------------------------- */

    /** Capture the primary melee base damage for the secondary sweep formula. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPrimary(@NotNull EntityDamageByEntityEvent event) {
        if (event.getCause() == DamageCause.ENTITY_ATTACK && event.getDamager() instanceof Player attacker) {
            lastMainDamage.put(attacker.getUniqueId(), event.getDamage());
        }
    }

    /**
     * The CT8c sweep gate: cancel a secondary sweep that lacks Sweeping Edge, or
     * (charge-gated) was below ≥195% charge; re-shape a surviving one to the CT8c
     * secondary damage. LOW priority leaves protection / indicator plugins room.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSweep(@NotNull EntityDamageByEntityEvent event) {
        if (event.getCause() != sweepCause || !(event.getDamager() instanceof Player attacker)) {
            return;
        }
        UUID id = attacker.getUniqueId();
        int level = Ct8cSweepEnchants.sweepingEdgeLevel(attacker.getInventory().getItemInMainHand());
        if (level <= 0) {
            event.setCancelled(true); // plain swords no longer sweep (spec §2.3)
            return;
        }
        if (chargeGated && !Ct8cChargeMath.sweepAllowed(view.currentScale(id))) {
            event.setCancelled(true); // sweep needs ≥195% charge (spec §2.3)
            return;
        }
        double main = lastMainDamage.getOrDefault(id, event.getDamage());
        event.setDamage(Ct8cSweepRatios.secondaryDamage(level, main));
    }

    /* --------------------------- axe anvil eligibility ---------------------------- */

    /**
     * Combat Test 8c lets a book+anvil apply Sweeping Edge / Fire Aspect / Looting /
     * Knockback onto axes (spec §2.3/§2.9). Where the base is an axe and the addition
     * is an enchanted book carrying one of those, augment the anvil result with the
     * eligible enchant(s) — vanilla otherwise rejects them on an axe.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnvil(@NotNull PrepareAnvilEvent event) {
        ItemStack base = event.getInventory().getItem(0);
        ItemStack addition = event.getInventory().getItem(1);
        if (base == null || addition == null || !isAxe(base.getType())
                || addition.getType() != Material.ENCHANTED_BOOK
                || !(addition.getItemMeta() instanceof EnchantmentStorageMeta book)) {
            return;
        }
        ItemStack result = event.getResult();
        ItemStack candidate = (result != null ? result : base).clone();
        boolean augmented = false;
        for (Map.Entry<Enchantment, Integer> stored : book.getStoredEnchants().entrySet()) {
            Enchantment enchant = stored.getKey();
            if (!Ct8cSweepEnchants.axeEligible(enchant)) {
                continue;
            }
            int level = Math.max(candidate.getEnchantmentLevel(enchant), stored.getValue());
            if (candidate.getEnchantmentLevel(enchant) != level) {
                candidate.addUnsafeEnchantment(enchant, level);
                augmented = true;
            }
        }
        if (augmented) {
            event.setResult(candidate);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        lastMainDamage.remove(event.getPlayer().getUniqueId());
    }

    /* -------------------------------- helpers -------------------------------- */

    private static boolean isAxe(Material material) {
        // "_AXE" excludes pickaxes ("...CKAXE"): only genuine axes match.
        return material.name().endsWith("_AXE");
    }

    private void logDegrade() {
        JavaPlugin.getPlugin(MentalPluginV5.class).getLogger().info(
                "ct8c-sweep: the ENTITY_SWEEP_ATTACK damage cause is absent on this version (lands 1.11) — "
                        + "sweep splash arrives as plain ENTITY_ATTACK and cannot be discriminated, so this "
                        + "feature is a documented no-op here; vanilla sweep remains.");
    }
}
