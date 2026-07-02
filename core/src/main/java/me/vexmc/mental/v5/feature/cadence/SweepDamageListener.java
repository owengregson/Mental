package me.vexmc.mental.v5.feature.cadence;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.jetbrains.annotations.NotNull;

/**
 * The event (server-rule / vanilla-path) half of sword-sweep suppression: cancels
 * every {@code ENTITY_SWEEP_ATTACK} damage event so swords deal single-target
 * damage as in 1.7/1.8 (the retired {@code module.rules.sweep.SweepModule} on the
 * v5 seam). Cancelling the damage also prevents the sweep knockback.
 *
 * <p>Shared by {@code SweepUnit} (the {@code disable-sword-sweep} feature) and
 * {@code AttackCooldownUnit} (which must re-disable sweep, since raising the
 * charge to full satisfies vanilla's {@code scale > 0.9} sweep gate — mandate
 * B5(d)); each registers its own instance in its own scope, so the cancel dies
 * with whichever scope registered it. Cancelling an already-cancelled event when
 * both are enabled is idempotent. Priority LOW leaves protection/damage-indicator
 * plugins room to react first. Folia-safe: the event fires on the victim's region
 * thread, so the inline cancel needs no scheduling hop.</p>
 */
public final class SweepDamageListener implements Listener {

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSweep(@NotNull EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
            event.setCancelled(true);
        }
    }
}
