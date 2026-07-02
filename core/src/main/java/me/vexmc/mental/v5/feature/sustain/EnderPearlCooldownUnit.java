package me.vexmc.mental.v5.feature.sustain;

import me.vexmc.mental.common.scheduling.Scheduling;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import org.bukkit.Material;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Removes the 1.9 ender-pearl throw cooldown, restoring 1.8 pearl spam (the
 * retired {@code module.consumable.EnderPearlCooldownModule} on the v5 seam).
 *
 * <p>{@code EnderpearlItem.use} sets the 20-tick cooldown before the projectile
 * spawns, so by the time {@link ProjectileLaunchEvent} fires it is already set;
 * clearing it via {@code setCooldown(ENDER_PEARL, 0)} is correct and sufficient.
 * On 1.21.2+ the cooldown moved to a {@code use_cooldown} component keyed by a
 * group Identifier — CraftBukkit's {@code setCooldown} resolves the group on
 * every version, so no branching. Routed through {@code Scheduling.runOn} for
 * Folia region-correctness. The listener exists only while the scope is open, so
 * it is zero-touch when disabled.</p>
 */
public final class EnderPearlCooldownUnit implements FeatureUnit, Listener {

    private final Scheduling scheduling;

    public EnderPearlCooldownUnit(@NotNull Scheduling scheduling) {
        this.scheduling = scheduling;
    }

    @Override
    public Feature descriptor() {
        return Feature.ENDER_PEARL_COOLDOWN;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        scope.listen(this);
    }

    @EventHandler(ignoreCancelled = true)
    public void onLaunch(@NotNull ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) {
            return;
        }
        if (!(pearl.getShooter() instanceof Player player)) {
            return;
        }
        scheduling.runOn(player, () -> player.setCooldown(Material.ENDER_PEARL, 0), () -> {});
    }
}
