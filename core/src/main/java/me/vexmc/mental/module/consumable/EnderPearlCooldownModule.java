package me.vexmc.mental.module.consumable;

import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.engine.CombatModule;
import org.bukkit.Material;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Removes the 1.9 ender-pearl throw cooldown, restoring 1.8 pearl spam.
 *
 * <p>Era truth: 1.8.9 had no ender-pearl cooldown. The 20-tick (1 s) cooldown
 * was added in 1.9 inside {@code EnderpearlItem.use}, which sets the cooldown
 * before spawning the projectile. On 1.21.2+ the cooldown was migrated from a
 * player field to a {@code use_cooldown} data component keyed by an Identifier
 * (the cooldown group). The cross-version-stable lever to clear it is
 * {@link Player#setCooldown(Material, int)} with {@code ticks=0}: CraftBukkit
 * resolves the right cooldown group internally on every supported version, so
 * callers need no version branching.</p>
 *
 * <p>Timing: {@code EnderpearlItem.use} sets the cooldown before the projectile
 * is spawned, so by the time {@link ProjectileLaunchEvent} fires the cooldown is
 * already set — clearing it then is correct and sufficient.</p>
 *
 * <p>Folia safety: the projectile spawns in the shooter's region, so
 * {@link ProjectileLaunchEvent} already fires on the correct region thread.
 * We route the {@link Player#setCooldown} call through
 * {@link me.vexmc.mental.common.scheduling.Scheduling#runOn} anyway for
 * consistency with the rest of the codebase and to remain correct if the
 * scheduling topology changes.</p>
 *
 * <p>No pure algorithmic logic exists to unit-test here (the module is
 * event-glue: event fires → {@code setCooldown(0)}). Behaviour is covered by
 * the integration matrix.</p>
 *
 * <p>Zero-touch: when disabled (the default), this module registers no listeners
 * and has no effect on the game.</p>
 */
public final class EnderPearlCooldownModule extends CombatModule implements Listener {

    public EnderPearlCooldownModule(@NotNull MentalServices services) {
        super(services,
                "disable-enderpearl-cooldown",
                "Ender Pearl Cooldown Removal",
                "Removes the 1.9 1-second ender-pearl throw cooldown, restoring 1.8 pearl spam.",
                DebugCategory.CONFIG);
    }

    @Override
    public boolean configEnabled() {
        return services.config().enderPearl().enabled();
    }

    @Override
    protected void onEnable() {
        listen(this);
    }

    @Override
    protected void onDisable() {
        // Listeners are unregistered automatically by CombatModule.disable().
    }

    /**
     * Clears the ender-pearl cooldown immediately after the pearl is launched.
     *
     * <p>By the time this event fires, {@code EnderpearlItem.use} has already set
     * the 20-tick cooldown; we clear it with {@code setCooldown(ENDER_PEARL, 0)}
     * routed through the entity scheduler so the call lands on the player's owning
     * region thread (required on Folia; a no-op distinction on Paper).</p>
     */
    @EventHandler(ignoreCancelled = true)
    public void onLaunch(@NotNull ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof EnderPearl pearl)) {
            return;
        }
        if (!(pearl.getShooter() instanceof Player player)) {
            return;
        }
        // Route through runOn so the setCooldown call lands on the player's owning
        // region thread — required for Folia correctness and consistent with every
        // other entity-state mutation in this codebase (Scheduling.runOn →
        // EntityScheduler.run on Folia, main thread on Paper).
        services.scheduling().runOn(player,
                () -> player.setCooldown(Material.ENDER_PEARL, 0),
                () -> {});
        debug.log(() -> "cleared ender-pearl cooldown for " + player.getName());
    }
}
