package me.vexmc.mental.module.rules.sweep;

import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.engine.CombatModule;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Disables the 1.9 sword sweep attack, restoring single-target sword damage
 * as it existed in 1.7.10 and 1.8.9.
 *
 * <p>Era truth: the sweep attack was added in 1.9 as part of the same change
 * that introduced the attack cooldown and the {@code sweep_attack} particle.
 * In 1.7.10 and 1.8.9 every sword hit was single-target; disabling the sweep
 * here restores that behaviour.</p>
 *
 * <p>Mechanism (Bukkit event): when the module is enabled, {@link #onEnable()}
 * registers {@code this} as a Bukkit {@link Listener} via
 * {@link CombatModule#listen(Listener)}; the registration is automatically
 * undone on disable. The sweep-attack particle is suppressed separately by
 * {@link SweepParticleListener}, which is registered for the plugin lifetime
 * in {@code MentalPlugin} and gates on the same config flag.</p>
 *
 * <p>Folia safety: {@link EntityDamageEvent} fires on the victim's owning
 * region thread, so cancelling inline (no scheduling hop) is region-safe.</p>
 */
public final class SweepModule extends CombatModule implements Listener {

    public SweepModule(@NotNull MentalServices services) {
        super(services,
                "disable-sword-sweep",
                "Sword Sweep Disable",
                "Cancels the 1.9 sweep-attack damage event so swords deal single-target damage "
                        + "as in 1.7/1.8, and suppresses the sweep_attack particle via a netty listener.",
                DebugCategory.PACKETS);
    }

    @Override
    public boolean configEnabled() {
        return services.config().sweep().enabled();
    }

    @Override
    protected void onEnable() {
        // Register this class as a Bukkit listener; CombatModule.listen()
        // auto-unregisters it when the module is disabled.
        listen(this);
    }

    @Override
    protected void onDisable() {
        // Bukkit listener teardown is handled automatically by CombatModule.
        // SweepParticleListener is plugin-lifetime and gates on the config flag.
    }

    /**
     * Cancels every {@link EntityDamageEvent.DamageCause#ENTITY_SWEEP_ATTACK}
     * hit.  Cancelling the damage event also prevents the sweep knockback that
     * would otherwise be applied inside the hurt pipeline.
     *
     * <p>Priority {@code LOW} matches OCM's recommended setting for this event
     * ("changed from HIGHEST to LOWEST to support DamageIndicator plugin"),
     * giving protection plugins and damage indicators room to react first.</p>
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSweep(@NotNull EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
            event.setCancelled(true);
            debug.log(() -> "sweep-attack damage cancelled on " + event.getEntity().getName());
        }
    }
}
