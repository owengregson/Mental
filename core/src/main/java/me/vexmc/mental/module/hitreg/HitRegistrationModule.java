package me.vexmc.mental.module.hitreg;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.common.scheduling.TaskHandle;
import me.vexmc.mental.engine.CombatModule;
import me.vexmc.mental.module.knockback.KnockbackHints;
import me.vexmc.mental.module.knockback.KnockbackPipeline;
import me.vexmc.mental.module.knockback.ServerTickClock;
import me.vexmc.mental.module.knockback.VictimMotion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Asynchronous hit registration.
 *
 * <p>Owns the packet listener, the limiter and gate, and one snapshot task
 * per online player — each scheduled on its own entity scheduler, so every
 * snapshot is taken on the player's owning thread (the pattern that is
 * region-correct on Folia and main-thread on Paper, with identical code).</p>
 */
public final class HitRegistrationModule extends CombatModule implements Listener {

    private final CpsLimiter limiter = new CpsLimiter();
    private final PlayerStateCache stateCache;
    private final HitFeedbackGate feedbackGate = new HitFeedbackGate();
    private final PositionHistory positionHistory = new PositionHistory();
    private final ConcurrentHashMap<UUID, TaskHandle> snapshotTasks = new ConcurrentHashMap<>();
    private final VictimMotion ledger;
    private final KnockbackPipeline pipeline;
    private final KnockbackHints hints;

    private PacketListenerCommon handle;

    public HitRegistrationModule(
            @NotNull MentalServices services,
            @NotNull VictimMotion ledger,
            @NotNull KnockbackPipeline pipeline,
            @NotNull KnockbackHints hints,
            @NotNull ServerTickClock clock) {
        super(services, "hit-registration", "Hit Registration",
                "Netty-thread attack interception with owning-thread damage and pre-sent feedback.",
                DebugCategory.HITREG);
        this.ledger = ledger;
        this.pipeline = pipeline;
        this.hints = hints;
        this.stateCache = new PlayerStateCache(clock);
    }

    @Override
    public boolean configEnabled() {
        return services.config().hitReg().enabled();
    }

    @Override
    protected void onEnable() {
        listen(this);
        HitPacketListener listener = new HitPacketListener(
                services, limiter, new HitApplier(services), stateCache, feedbackGate,
                new FeedbackSenders(), positionHistory, pipeline, hints);
        handle = PacketEvents.getAPI().getEventManager()
                .registerListener(listener, PacketListenerPriority.NORMAL);
        for (Player player : Bukkit.getOnlinePlayers()) {
            track(player);
        }
        debug.log(() -> "listening (fast-path=" + services.config().hitReg().fastPath()
                + ", pre-send=" + services.config().hitReg().preSendFeedback() + ")");
    }

    @Override
    protected void onDisable() {
        if (handle != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(handle);
            handle = null;
        }
        snapshotTasks.values().forEach(TaskHandle::cancel);
        snapshotTasks.clear();
        limiter.clear();
        stateCache.clear();
        feedbackGate.clear();
        positionHistory.clear();
    }

    @EventHandler
    public void onPlayerJoin(@NotNull PlayerJoinEvent event) {
        stateCache.update(event.getPlayer(), ledger, services.ocmGate(), services.knockbackProfiles());
        track(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        TaskHandle task = snapshotTasks.remove(id);
        if (task != null) {
            task.cancel();
        }
        limiter.forget(id);
        stateCache.forget(id);
        feedbackGate.forget(id);
        positionHistory.forget(id);
    }

    private void track(@NotNull Player player) {
        UUID id = player.getUniqueId();
        snapshotTasks.compute(id, (uuid, existing) -> {
            if (existing != null) {
                existing.cancel();
            }
            return services.scheduling().repeatOn(
                    player, 1L, 1L,
                    () -> {
                        stateCache.update(player, ledger, services.ocmGate(),
                                services.knockbackProfiles());
                        Location location = player.getLocation();
                        positionHistory.record(uuid, location.getX(), location.getY(),
                                location.getZ(), System.nanoTime());
                    },
                    () -> {
                        snapshotTasks.remove(uuid);
                        stateCache.forget(uuid);
                        positionHistory.forget(uuid);
                    });
        });
    }
}
