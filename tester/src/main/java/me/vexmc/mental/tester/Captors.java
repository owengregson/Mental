package me.vexmc.mental.tester;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** MONITOR-priority observers for the values modules actually produced. */
public final class Captors implements Listener {

    private final Map<UUID, Vector> velocities = new ConcurrentHashMap<>();
    private final Map<UUID, Double> damages = new ConcurrentHashMap<>();

    public static @NotNull Captors register(@NotNull Plugin plugin) {
        Captors captors = new Captors();
        plugin.getServer().getPluginManager().registerEvents(captors, plugin);
        return captors;
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(@NotNull PlayerVelocityEvent event) {
        velocities.put(event.getPlayer().getUniqueId(), event.getVelocity().clone());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(@NotNull EntityDamageByEntityEvent event) {
        damages.put(event.getEntity().getUniqueId(), event.getDamage());
    }

    public @Nullable Vector velocityOf(@NotNull UUID player) {
        return velocities.get(player);
    }

    public @Nullable Double damageOf(@NotNull UUID entity) {
        return damages.get(entity);
    }

    public void reset() {
        velocities.clear();
        damages.clear();
    }
}
