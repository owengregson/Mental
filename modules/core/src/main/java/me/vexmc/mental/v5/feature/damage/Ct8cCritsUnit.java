package me.vexmc.mental.v5.feature.damage;

import me.vexmc.mental.kernel.math.DamageTables;
import me.vexmc.mental.platform.CritPosture;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;

/**
 * Combat Test 8c critical hits (spec §2.3) — the {@code CritFallbackUnit} twin,
 * CT8c policy. A crit is a flat ×1.5 on the WHOLE BASE, so — because the enchant
 * additive is already inside the BASE ({@code Ct8cDamageUnit} folded Sharpness /
 * Cleaving / Impaling in) — the enchant is multiplied too, the exact inverse of
 * the era rule (spec §2.3: "vanilla adds it after"). The CT8c crit posture is the
 * era one <em>with sprint allowed</em>, which is precisely {@link
 * DamageShaper#isLegacyCritical} (falling, off-ground, not climbing, not in
 * water, not blind, not riding — no sprint exclusion).
 *
 * <p>Runs at {@link EventPriority#LOW}, one bucket after {@code Ct8cDamageUnit}'s
 * {@code LOWEST} BASE write, so the ×1.5 always lands on the finished CT8c base
 * regardless of listener registration order. Standalone (ct8c-crits without
 * ct8c-damage) it multiplies whatever BASE exists — the vanilla weapon damage.</p>
 *
 * <p><b>Not double-critting vanilla.</b> A fast-path melee never runs {@code
 * Player#attack}, so its BASE carries no vanilla crit; a hit that DID reach
 * vanilla's path already got vanilla's ×1.5 only when fully charged AND not
 * sprinting — so this unit yields exactly there ({@code attackCharge > 0.9 &&
 * !sprinting}) and takes every sprint hit (which vanilla never crits, but CT8c
 * does). Enabling both ct8c-crits and the era {@code crit-fallback}, or the
 * fast-path {@code simulate-crits}, double-crits; the bundles keep them mutually
 * exclusive.</p>
 *
 * <p>Zero-touch: assembled only when enabled; touches only player melee.</p>
 */
public final class Ct8cCritsUnit implements FeatureUnit, Listener {

    @Override
    public Feature descriptor() {
        return Feature.CT8C_CRITS;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        scope.listen(this);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    @SuppressWarnings("deprecation") // the granular BASE modifier is the only pre-armour amount hook Bukkit exposes
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || !(event.getDamager() instanceof Player attacker)
                || !(event.getEntity() instanceof LivingEntity)) {
            return;
        }
        // CT8c crit posture = the era posture with sprint allowed (spec §2.3).
        if (!DamageShaper.isLegacyCritical(attacker)) {
            return;
        }
        // Do not double-crit a hit vanilla already ×1.5'd: that only happens on a
        // fully-charged, NON-sprint vanilla landing. CritPosture.attackCharge is
        // the boot-resolved accessor (fully-charged default on the legacy tier).
        if (CritPosture.attackCharge(attacker) > 0.9f && !attacker.isSprinting()) {
            return;
        }
        // The flat ×1.5 (spec §2.3), sourced from the kernel so nothing re-derives it.
        double base = event.getDamage(DamageModifier.BASE);
        event.setDamage(DamageModifier.BASE, base * DamageTables.critMultiplier());
    }
}
