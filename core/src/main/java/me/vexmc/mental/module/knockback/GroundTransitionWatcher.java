package me.vexmc.mental.module.knockback;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.common.scheduling.TaskHandle;
import me.vexmc.mental.platform.Attributes;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Replica of the legacy movement-packet bookkeeping that wrote the server's
 * motion fields between knockbacks (see {@link VictimMotion}).
 *
 * <p>The era handler ran on every client movement packet, in arrival order: a
 * grounded player reporting airborne-and-rising was a jump ({@code motY =
 * 0.42} + the sprint facing push), and a landing re-equilibrated the
 * vertical. Two feeds replicate it:</p>
 *
 * <ul>
 *   <li><b>Packet feed</b> ({@link #onClientMovement}, from the netty ground
 *       tap) — the client's own position packets, judged at arrival exactly
 *       like the era handler. This is the primary feed for real clients: a
 *       tick-sampled view stamps the jump one sample late (the sampler races
 *       the tick's packet processing) and that one lost decay tick ships
 *       measurably high combo verticals (era 0.2477 vs sampled 0.2862 at the
 *       same staging — worse at real ping, where sampling can also flicker
 *       across a touchdown-relaunch boundary and re-stamp a spent flight).
 *       The packet's own {@code onGround} flag is the client's report — the
 *       modern server-side flag flip never appears here, so the packet feed
 *       needs no deferral on liftoffs that carry a position.</li>
 *   <li><b>Tick sampler</b> ({@link #sample}, per-player task) — the fallback
 *       for players that never send movement packets (the tester's fake
 *       players, synthetic connections). Skipped permanently for any player
 *       the packet feed has seen; the task keeps running only to refresh the
 *       gravity cache the netty-thread feed cannot read itself.</li>
 * </ul>
 *
 * <p>Pure observation: nothing here touches gameplay, matching the
 * sprint-tracker's contract. It is registered at plugin level because the
 * ledger underneath every knockback module needs era-correct baselines even
 * while individual modules are toggled.</p>
 */
public final class GroundTransitionWatcher implements Listener {

    /** A position jump this large is a teleport, not movement — resync, never stamp. */
    private static final double TELEPORT_RESET_DISTANCE = 8.0;

    private record GroundState(boolean grounded, double y) {}

    /** Last judged client-packet state; {@code yaw} is the last rotation-bearing packet's. */
    private record PacketState(boolean grounded, double y, float yaw) {}

    private final MentalServices services;
    private final VictimMotion ledger;
    private final ConcurrentHashMap<UUID, GroundState> lastStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PacketState> packetStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> clientSprint = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Double> gravityCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, TaskHandle> tasks = new ConcurrentHashMap<>();

    public GroundTransitionWatcher(@NotNull MentalServices services, @NotNull VictimMotion ledger) {
        this.services = services;
        this.ledger = ledger;
    }

    /** Starts watching everyone currently online (reload/late-enable safety). */
    public void watchOnlinePlayers() {
        for (Player player : services.plugin().getServer().getOnlinePlayers()) {
            watch(player);
        }
    }

    public void shutdown() {
        tasks.values().forEach(TaskHandle::cancel);
        tasks.clear();
        lastStates.clear();
        packetStates.clear();
        clientSprint.clear();
        gravityCache.clear();
    }

    @EventHandler
    public void onJoin(@NotNull PlayerJoinEvent event) {
        watch(event.getPlayer());
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        TaskHandle task = tasks.remove(id);
        if (task != null) {
            task.cancel();
        }
        forgetStates(id);
        clientSprint.remove(id);
        gravityCache.remove(id);
    }

    @EventHandler
    public void onChangedWorld(@NotNull PlayerChangedWorldEvent event) {
        forgetStates(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(@NotNull PlayerTeleportEvent event) {
        // The client keeps sending packets for the OLD position until it
        // acknowledges the teleport; judging a transition across that seam
        // would stamp a phantom jump. Resync from the next packet instead.
        forgetStates(event.getPlayer().getUniqueId());
    }

    /**
     * The packet feed: one client movement packet, judged at arrival on the
     * victim's own netty thread — the era handler's exact cadence. Flag-only
     * packets cannot carry a rising verdict; a liftoff arriving on one is
     * deferred (state kept) until the next position-bearing packet, the same
     * way the era's jump bookkeeping keyed off the packet's y delta.
     */
    public void onClientMovement(
            @NotNull UUID id, boolean grounded, boolean hasPosition, double y,
            boolean hasRotation, float yaw, long nowNanos) {
        PacketState previous = packetStates.get(id);
        float knownYaw = hasRotation ? yaw : previous != null ? previous.yaw() : 0.0f;
        if (previous == null) {
            // First packet marks the player packet-fed (the sampler stands
            // down); judgments start once a baseline exists.
            packetStates.put(id, new PacketState(grounded, hasPosition ? y : 0.0, knownYaw));
            return;
        }
        double knownY = hasPosition ? y : previous.y();
        if (hasPosition && Math.abs(y - previous.y()) > TELEPORT_RESET_DISTANCE) {
            packetStates.put(id, new PacketState(grounded, y, knownYaw));
            return;
        }
        if (previous.grounded() == grounded) {
            packetStates.put(id, new PacketState(grounded, knownY, knownYaw));
            return;
        }
        if (!grounded && !hasPosition) {
            // Liftoff on a flag-only packet: rising is unknowable — hold the
            // previous state and judge on the next position-bearing packet.
            return;
        }
        packetStates.put(id, new PacketState(grounded, knownY, knownYaw));
        double gravity = gravityCache.getOrDefault(id, VictimMotion.DEFAULT_GRAVITY);
        // Tick-stamped so boundary reads can apply the era ordering: an
        // attack and a victim movement packet landing in the same tick were
        // judged attack-first (the attacker's connection slot ran before the
        // victim's). Bukkit#getCurrentTick is a plain static counter — safe
        // to read from the netty thread at tick grain.
        int tick = Bukkit.getCurrentTick();
        if (grounded) {
            debugLog(id, () -> "ground-tap LANDING y=" + knownY);
            ledger.recordLanding(id, nowNanos, gravity, tick);
        } else {
            boolean rising = y > previous.y();
            boolean sprinting = clientSprint.getOrDefault(id, false);
            debugLog(id, () -> "ground-tap LIFTOFF rising=" + rising + " sprint=" + sprinting
                    + " y=" + y + " prevY=" + previous.y());
            ledger.recordLiftoff(id, rising, sprinting, knownYaw, nowNanos, gravity, tick);
        }
    }

    /**
     * The client's own sprint sync (entity-action packets, arrival order) —
     * the same source that drove the era server's sprint flag, readable from
     * the netty thread where the live entity is not.
     */
    public void onClientSprint(@NotNull UUID id, boolean sprinting) {
        clientSprint.put(id, sprinting);
    }

    private void forgetStates(@NotNull UUID id) {
        lastStates.remove(id);
        packetStates.remove(id);
    }

    private void watch(@NotNull Player player) {
        UUID id = player.getUniqueId();
        tasks.compute(id, (uuid, existing) -> {
            if (existing != null) {
                existing.cancel();
            }
            return services.scheduling().repeatOn(
                    player, 1L, 1L,
                    () -> sample(player),
                    () -> {
                        tasks.remove(uuid);
                        lastStates.remove(uuid);
                    });
        });
    }

    @SuppressWarnings("deprecation") // Entity#isOnGround: the client-reported value, exactly what the era handler read
    private void sample(@NotNull Player player) {
        UUID id = player.getUniqueId();
        gravityCache.put(id, Attributes.valueOr(
                player, Attributes.gravity(), VictimMotion.DEFAULT_GRAVITY));
        if (packetStates.containsKey(id)) {
            return; // packet-fed: the netty tap owns this player's transitions
        }
        Location location = player.getLocation();
        boolean grounded = player.isOnGround();
        GroundState previous = lastStates.put(id, new GroundState(grounded, location.getY()));
        if (previous == null || previous.grounded() == grounded) {
            return;
        }
        if (!grounded && location.getY() == previous.y()) {
            // Modern servers flip a knocked player's own onGround flag on
            // the hit tick, before the client's first airborne movement
            // packet arrives — the era flag was packet-driven only. With
            // the position still at its grounded value, rising cannot be
            // judged era-faithfully: restore the grounded state and decide
            // on the next sample, when the client's first airborne position
            // (the exact packet the era jump bookkeeping evaluated) is in.
            // Without this, every knock liftoff reads rising=false and the
            // ledger free-falls from equilibrium instead of stamping the
            // 0.42 jump impulse (measured: combo hit 2 then ships vy ~0.07
            // where real 1.8.9 ships 0.2846).
            lastStates.put(id, previous);
            return;
        }
        long now = System.nanoTime();
        double gravity = Attributes.valueOr(player, Attributes.gravity(), VictimMotion.DEFAULT_GRAVITY);
        if (grounded) {
            debugLog(id, () -> "ground-watch " + player.getName() + " LANDING y=" + location.getY());
            ledger.recordLanding(id, now, gravity);
        } else {
            boolean rising = location.getY() > previous.y();
            debugLog(id, () -> "ground-watch " + player.getName() + " LIFTOFF rising=" + rising
                    + " y=" + location.getY() + " prevY=" + previous.y());
            ledger.recordLiftoff(id, rising, player.isSprinting(), location.getYaw(), now, gravity);
        }
    }

    private void debugLog(@NotNull UUID id, @NotNull java.util.function.Supplier<String> message) {
        services.debug().log(DebugCategory.KNOCKBACK, () -> message.get() + " [" + id + "]");
    }
}
