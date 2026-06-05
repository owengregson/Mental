package me.vexmc.mental.module.knockback;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.scheduling.TaskHandle;
import me.vexmc.mental.platform.Attributes;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Per-tick replica of the legacy movement-packet bookkeeping that wrote the
 * server's motion fields between knockbacks (see {@link VictimMotion}).
 *
 * <p>The era handler ran on every client movement packet: a grounded player
 * reporting airborne-and-rising was a jump ({@code motY = 0.42} + the sprint
 * facing push), and the simulated move's ground collision re-equilibrated
 * the vertical on landing. Mental samples each player once per tick from
 * their owning thread — the same cadence a vanilla client's movement packets
 * arrive at — and feeds the identical transitions into the ledger.</p>
 *
 * <p>Pure observation: nothing here touches gameplay, matching the
 * sprint-tracker's contract. It is registered at plugin level because the
 * ledger underneath every knockback module needs era-correct baselines even
 * while individual modules are toggled.</p>
 */
public final class GroundTransitionWatcher implements Listener {

    private record GroundState(boolean grounded, double y) {}

    private final MentalServices services;
    private final VictimMotion ledger;
    private final ConcurrentHashMap<UUID, GroundState> lastStates = new ConcurrentHashMap<>();
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
        lastStates.remove(id);
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
        Location location = player.getLocation();
        boolean grounded = player.isOnGround();
        GroundState previous = lastStates.put(id, new GroundState(grounded, location.getY()));
        if (previous == null || previous.grounded() == grounded) {
            return;
        }
        long now = System.nanoTime();
        double gravity = Attributes.valueOr(player, Attributes.gravity(), VictimMotion.DEFAULT_GRAVITY);
        if (grounded) {
            ledger.recordLanding(id, now, gravity);
        } else {
            boolean rising = location.getY() > previous.y();
            ledger.recordLiftoff(id, rising, player.isSprinting(), location.getYaw(), now, gravity);
        }
    }
}
