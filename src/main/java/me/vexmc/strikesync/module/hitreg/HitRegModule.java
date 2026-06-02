package me.vexmc.strikesync.module.hitreg;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import me.vexmc.strikesync.core.Module;
import me.vexmc.strikesync.core.StrikeSyncService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Owns the PacketEvents {@code INTERACT_ENTITY} listener, the per-player CPS
 * state, the per-tick {@link PlayerStateCache} and the dispatch + applier
 * components used by the fast hit-registration path.
 *
 * <p>Designed for hot-reload: {@link #reload()} just inspects the latest
 * {@code HitRegSettings} and starts/stops the underlying PacketEvents listener
 * without ever creating duplicates. The cache update task is owned by the
 * module's enable/disable cycle, not by per-config flags, so it runs whenever
 * the module is up.
 */
public final class HitRegModule implements Module, Listener {

    /** How often the per-player state cache is refreshed, in ticks. */
    private static final long CACHE_PERIOD_TICKS = 1L;

    private final StrikeSyncService service;
    private final CpsLimiter limiter = new CpsLimiter();
    private final PlayerStateCache stateCache = new PlayerStateCache();
    private final HitFeedbackGate feedbackGate = new HitFeedbackGate();
    private final HitDispatcher dispatcher;
    private final HitApplier applier;

    private HitPacketListener listener;
    private PacketListenerCommon handle;
    private BukkitTask cacheTask;
    private boolean enabledByConfig;
    private boolean registeredAsListener;

    public HitRegModule(StrikeSyncService service) {
        this.service = service;
        this.dispatcher = new HitDispatcher(service.plugin());
        this.applier = new HitApplier(service);
    }

    @Override
    public String id() {
        return "hitreg";
    }

    @Override
    public void enable() {
        if (!registeredAsListener) {
            Bukkit.getPluginManager().registerEvents(this, service.plugin());
            registeredAsListener = true;
        }
        startCacheTask();
        applyEnabledState(service.config().hitReg().enabled());
    }

    @Override
    public void disable() {
        applyEnabledState(false);
        stopCacheTask();
        limiter.clear();
        stateCache.clear();
        feedbackGate.clear();
        if (registeredAsListener) {
            HandlerList.unregisterAll(this);
            registeredAsListener = false;
        }
    }

    @Override
    public void reload() {
        applyEnabledState(service.config().hitReg().enabled());
    }

    public boolean isListening() {
        return enabledByConfig && handle != null;
    }

    /* ----------------------------- bukkit events ----------------------------- */

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Seed the cache immediately so the first hit a player takes /
        // delivers can still benefit from the fast path.
        stateCache.update(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        limiter.forget(event.getPlayer().getUniqueId());
        stateCache.forget(event.getPlayer().getUniqueId());
        feedbackGate.forget(event.getPlayer().getUniqueId());
    }

    /* ----------------------------- internals ----------------------------- */

    private void applyEnabledState(boolean shouldEnable) {
        if (shouldEnable && handle == null) {
            startHandler();
        } else if (!shouldEnable && handle != null) {
            stopHandler();
        }
        enabledByConfig = shouldEnable;
    }

    private void startHandler() {
        listener = new HitPacketListener(service, limiter, dispatcher, applier, stateCache, feedbackGate);
        handle = PacketEvents.getAPI().getEventManager().registerListener(
                listener, PacketListenerPriority.NORMAL);
        service.log().info("Async hit registration started (fast-path: "
                + service.config().hitReg().fastPath() + ", pre-send-feedback: "
                + service.config().hitReg().preSendFeedback() + ").");
    }

    private void stopHandler() {
        try {
            if (handle != null) {
                PacketEvents.getAPI().getEventManager().unregisterListener(handle);
            }
        } finally {
            handle = null;
            listener = null;
            service.log().info("Async hit registration stopped.");
        }
    }

    /**
     * Refresh every online player's snapshot once per tick. The work is
     * scheduled via the Bukkit scheduler (not entity scheduler) because we
     * iterate all players together — the per-player update body itself
     * accesses only that player's state, so it's safe under non-Folia Paper.
     * On Folia, this iteration would need per-entity dispatch; we cross that
     * bridge if/when Folia support is requested.
     */
    private void startCacheTask() {
        if (cacheTask != null) return;
        cacheTask = Bukkit.getScheduler().runTaskTimer(service.plugin(), () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                stateCache.update(p);
            }
        }, CACHE_PERIOD_TICKS, CACHE_PERIOD_TICKS);
    }

    private void stopCacheTask() {
        if (cacheTask != null) {
            cacheTask.cancel();
            cacheTask = null;
        }
    }
}
