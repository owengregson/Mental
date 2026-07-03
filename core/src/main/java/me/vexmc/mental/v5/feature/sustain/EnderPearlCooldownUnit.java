package me.vexmc.mental.v5.feature.sustain;

import me.vexmc.mental.platform.Cooldowns;
import me.vexmc.mental.platform.Scheduling;
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
import org.bukkit.plugin.Plugin;
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
 *
 * <p><strong>Pre-1.11 is a documented loud no-op.</strong> The item-cooldown API
 * ({@code HumanEntity#setCooldown(Material,int)}) first appears at 1.11.2, and
 * vanilla {@code <=} 1.10 has no ender-pearl throw cooldown at all — that native
 * absence <em>is</em> the era state this feature restores on 1.11+. So on
 * 1.9.4/1.10.2 the unit registers no listener and logs one line at enable rather
 * than throwing a {@code NoSuchMethodError} clearing a cooldown the server never
 * sets (mandate B10: work or refuse loudly, never throw). Presence is a boot
 * probe ({@link Cooldowns}), never a version parse.</p>
 */
public final class EnderPearlCooldownUnit implements FeatureUnit, Listener {

    private final Plugin plugin;
    private final Scheduling scheduling;

    public EnderPearlCooldownUnit(@NotNull Plugin plugin, @NotNull Scheduling scheduling) {
        this.plugin = plugin;
        this.scheduling = scheduling;
    }

    @Override
    public Feature descriptor() {
        return Feature.ENDER_PEARL_COOLDOWN;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        if (!Cooldowns.itemCooldownSupported()) {
            plugin.getLogger().info("ender-pearl-cooldown: the item-cooldown API is absent below 1.11.2 and "
                    + "vanilla <=1.10 has no ender-pearl throw cooldown — the era state this feature restores "
                    + "is already native on this version, so it is a no-op here (nothing to clear).");
            return;
        }
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
