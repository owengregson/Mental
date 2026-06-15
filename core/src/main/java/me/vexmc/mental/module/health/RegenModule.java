package me.vexmc.mental.module.health;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.common.scheduling.TaskHandle;
import me.vexmc.mental.engine.CombatModule;
import me.vexmc.mental.platform.Attributes;
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
 * Restores the 1.8 natural health regeneration model and suppresses the 1.9+
 * saturated-regen replacement.
 *
 * <h2>What the 1.8 model did</h2>
 * <p>FoodStats (xg.java, decomp-1.8.9) kept a {@code foodTickTimer}
 * that incremented every server tick. When it reached 80 the server called
 * {@code heal(1.0f)} (half a heart) and {@code addExhaustion(3.0f)}, then
 * reset the timer. The heal only fired when {@code foodLevel >= 18}, the
 * player was alive, health was below max, and the {@code naturalRegeneration}
 * gamerule was on.</p>
 *
 * <h2>What this module does</h2>
 * <ol>
 *   <li><b>Suppress vanilla 1.9+ regen</b> — cancels every
 *       {@link EntityRegainHealthEvent} whose reason is
 *       {@link EntityRegainHealthEvent.RegainReason#SATIATED} for players
 *       while the module is active. This covers both the fast saturated path
 *       (every 10 ticks) and the slow unsaturated path (every 80 ticks) that
 *       1.9 introduced.</li>
 *   <li><b>Era heal tick</b> — each online player gets a dedicated 80-tick
 *       repeating task via {@link me.vexmc.mental.common.scheduling.Scheduling#repeatOn},
 *       which lands on the player's own region thread (Folia-correct). The
 *       task checks {@link RegenMath#shouldHeal} and, when the gate passes,
 *       heals 1 HP and adds 3.0f exhaustion.</li>
 * </ol>
 *
 * <h2>Threading</h2>
 * <p>{@link EntityRegainHealthEvent} fires on the player's region thread —
 * the cancel is safe inline. All health reads and writes inside the
 * {@code repeatOn} callback are on that same region thread. The
 * {@link #handles} map is {@link ConcurrentHashMap} because cancel calls
 * from {@link #onDisable()} may race with the retired callback clearing the
 * entry.</p>
 *
 * <h2>Zero-touch</h2>
 * <p>When disabled (the default), this module registers no listeners, starts
 * no tasks, and leaves vanilla regen completely untouched.</p>
 */
public final class RegenModule extends CombatModule implements Listener {

    /**
     * Per-player repeating heal tasks, keyed by UUID.  Entries are added in
     * {@link #startTask(Player)} and removed either by the retired callback
     * (entity left / became invalid) or explicitly in {@link #cancelTask(UUID)}.
     */
    private final ConcurrentHashMap<UUID, TaskHandle> handles = new ConcurrentHashMap<>();

    public RegenModule(@NotNull MentalServices services) {
        super(services,
                "old-player-regen",
                "Old Player Regen",
                "Restores 1.8 natural health regen (1 HP / 80 ticks when fed), "
                        + "suppressing the 1.9+ fast saturated-regen model.",
                DebugCategory.CONFIG);
    }

    @Override
    public boolean configEnabled() {
        return services.config().regen().enabled();
    }

    @Override
    protected void onEnable() {
        listen(this);
        // Kick off per-player tasks for everyone already online when the module
        // is toggled on (e.g. a live /mental reload).  Each call schedules work
        // on that player's own region thread — no shared mutation loop.
        for (Player player : Bukkit.getOnlinePlayers()) {
            startTask(player);
        }
        debug.log(() -> "old-player-regen enabled; started tasks for "
                + handles.size() + " online player(s)");
    }

    @Override
    protected void onDisable() {
        // Cancel every in-flight task so no heal fires after the module is
        // switched off — zero-touch invariant.
        handles.values().forEach(TaskHandle::cancel);
        handles.clear();
        debug.log(() -> "old-player-regen disabled; all regen tasks cancelled");
    }

    /* ------------------------------------------------------------------ */
    /*  Vanilla 1.9+ regen suppression                                    */
    /* ------------------------------------------------------------------ */

    /**
     * Cancels the 1.9+ saturated-regen event so the vanilla engine never
     * heals the player via that path while this module is active.
     *
     * <p>{@code RegainReason.SATIATED} covers both the fast path (every 10
     * ticks when saturation > 0) and the slow path (every 80 ticks without
     * saturation) that were introduced in 1.9. Cancelling here is safe —
     * the event fires on the player's region thread and we do no entity
     * writes.</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNaturalRegen(@NotNull EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        if (event.getRegainReason() != EntityRegainHealthEvent.RegainReason.SATIATED) {
            return;
        }
        // Suppress the 1.9+ model; our per-player task drives the 1.8 cadence.
        event.setCancelled(true);
    }

    /* ------------------------------------------------------------------ */
    /*  Player lifecycle                                                   */
    /* ------------------------------------------------------------------ */

    @EventHandler
    public void onJoin(@NotNull PlayerJoinEvent event) {
        startTask(event.getPlayer());
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        cancelTask(event.getPlayer().getUniqueId());
    }

    /* ------------------------------------------------------------------ */
    /*  Per-player task management                                         */
    /* ------------------------------------------------------------------ */

    /**
     * Starts the 80-tick era regen task for {@code player} if not already
     * tracked (guards against double-starting on rapid enable/join races).
     *
     * <p>The task is dispatched via {@link me.vexmc.mental.common.scheduling.Scheduling#repeatOn},
     * which uses {@code EntityScheduler.runAtFixedRate} on Folia (region-correct)
     * and {@code BukkitScheduler.runTaskTimer} on Paper (main-thread).  All
     * health reads and writes in the callback therefore land on the player's
     * owning region thread.</p>
     */
    private void startTask(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        if (handles.containsKey(uuid)) {
            return; // Already tracked — nothing to do.
        }

        TaskHandle[] holder = new TaskHandle[1];
        holder[0] = services.scheduling().repeatOn(
                player,
                RegenMath.INTERVAL_TICKS,
                RegenMath.INTERVAL_TICKS,
                () -> tick(player),
                () -> {
                    // Retired: entity became invalid (disconnect, world unload).
                    // Remove the entry so a subsequent join re-registers cleanly.
                    if (holder[0] != null) {
                        handles.remove(uuid, holder[0]);
                    }
                });
        handles.put(uuid, holder[0]);
        debug.log(() -> "started regen task for " + player.getName());
    }

    /**
     * Cancels and removes the task for {@code uuid}, if present.
     */
    private void cancelTask(@NotNull UUID uuid) {
        TaskHandle handle = handles.remove(uuid);
        if (handle != null) {
            handle.cancel();
            debug.log(() -> "cancelled regen task for " + uuid);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Era heal tick (runs on the player's region thread)                 */
    /* ------------------------------------------------------------------ */

    /**
     * Performs one era regen tick for {@code player}.
     *
     * <p><b>Must only be called from the {@code repeatOn} callback</b> — all
     * reads and writes here are safe because the callback lands on the player's
     * owning region thread.</p>
     *
     * <p>Exhaustion: we add {@link RegenMath#EXHAUSTION_PER_HEAL} (3.0f) per
     * heal and leave the rest of the exhaustion/saturation bookkeeping to
     * vanilla's food stat machinery, which clamps exhaustion at 40.0f and drains
     * saturation when exhaustion overflows. This matches the 1.8.9 behaviour
     * where {@code addExhaustion(3.0f)} was the only thing FoodStats did beyond
     * the heal itself.</p>
     */
    private void tick(@NotNull Player player) {
        int foodLevel = player.getFoodLevel();
        double health = player.getHealth();
        double maxHealth = Attributes.valueOr(player, Attributes.maxHealth(), 20.0);
        boolean naturalRegen = Boolean.TRUE.equals(
                player.getWorld().getGameRuleValue(GameRule.NATURAL_REGENERATION));

        if (!RegenMath.shouldHeal(foodLevel, health, maxHealth, naturalRegen)) {
            return;
        }

        double newHealth = Math.min(health + RegenMath.HEAL_AMOUNT, maxHealth);
        player.setHealth(newHealth);
        player.setExhaustion(player.getExhaustion() + RegenMath.EXHAUSTION_PER_HEAL);

        debug.log(() -> player.getName() + " era-regen tick: "
                + health + " → " + newHealth
                + " (food=" + foodLevel + ", exh+=" + RegenMath.EXHAUSTION_PER_HEAL + ")");
    }
}
