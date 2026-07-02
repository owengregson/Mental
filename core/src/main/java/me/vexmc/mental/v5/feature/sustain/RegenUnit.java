package me.vexmc.mental.v5.feature.sustain;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.common.scheduling.Scheduling;
import me.vexmc.mental.common.scheduling.TaskHandle;
import me.vexmc.mental.kernel.math.RegenMath;
import me.vexmc.mental.platform.Attributes;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Restores the 1.8 natural regen model and suppresses the 1.9+ satiated-regen
 * replacement (the retired {@code module.health.RegenModule} on the v5 seam).
 *
 * <ul>
 *   <li>cancels every {@code SATIATED} {@link EntityRegainHealthEvent} for players
 *       (both the 1.9 fast and slow satiated paths), so the vanilla engine never
 *       heals via that path while the feature is active;</li>
 *   <li>drives the era cadence with a dedicated per-player {@code repeatOn} task
 *       (kernel {@link RegenMath}: 1 HP + 3.0f exhaustion every 80 ticks when fed)
 *       — a per-player region task, never a global loop (Folia-correct).</li>
 * </ul>
 *
 * <p>Per-player tasks start for everyone online at enable and on join; they are
 * cancelled on quit, on retire, and — every one of them — on scope close, so a
 * disabled feature heals nothing (zero-touch).</p>
 */
public final class RegenUnit implements FeatureUnit, Listener {

    private final Scheduling scheduling;
    private final ConcurrentHashMap<UUID, TaskHandle> handles = new ConcurrentHashMap<>();

    public RegenUnit(@NotNull Scheduling scheduling) {
        this.scheduling = scheduling;
    }

    @Override
    public Feature descriptor() {
        return Feature.REGEN;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        scope.listen(this);
        scope.task(() -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                startTask(player);
            }
            return this::cancelAll;
        });
    }

    /* ------------------------------ suppression --------------------------- */

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNaturalRegen(@NotNull EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof Player
                && event.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED) {
            event.setCancelled(true); // our per-player task drives the 1.8 cadence
        }
    }

    /* ------------------------------ lifecycle ----------------------------- */

    @EventHandler
    public void onJoin(@NotNull PlayerJoinEvent event) {
        startTask(event.getPlayer());
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        cancelTask(event.getPlayer().getUniqueId());
    }

    /* --------------------------- task management -------------------------- */

    private void startTask(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        if (handles.containsKey(uuid)) {
            return; // already tracked
        }
        TaskHandle[] holder = new TaskHandle[1];
        holder[0] = scheduling.repeatOn(
                player, RegenMath.INTERVAL_TICKS, RegenMath.INTERVAL_TICKS,
                () -> tick(player),
                () -> {
                    if (holder[0] != null) {
                        handles.remove(uuid, holder[0]);
                    }
                });
        handles.put(uuid, holder[0]);
    }

    private void cancelTask(@NotNull UUID uuid) {
        TaskHandle handle = handles.remove(uuid);
        if (handle != null) {
            handle.cancel();
        }
    }

    private void cancelAll() {
        handles.values().forEach(TaskHandle::cancel);
        handles.clear();
    }

    /** One era heal tick — owning-thread only (the repeatOn callback). */
    private void tick(@NotNull Player player) {
        int foodLevel = player.getFoodLevel();
        double health = player.getHealth();
        double maxHealth = Attributes.valueOr(player, Attributes.maxHealth(), 20.0);
        boolean naturalRegen = Boolean.TRUE.equals(
                player.getWorld().getGameRuleValue(GameRule.NATURAL_REGENERATION));
        if (!RegenMath.shouldHeal(foodLevel, health, maxHealth, naturalRegen)) {
            return;
        }
        player.setHealth(Math.min(health + RegenMath.HEAL_AMOUNT, maxHealth));
        player.setExhaustion(player.getExhaustion() + RegenMath.EXHAUSTION_PER_HEAL);
    }
}
