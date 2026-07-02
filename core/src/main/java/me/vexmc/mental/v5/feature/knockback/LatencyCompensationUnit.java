package me.vexmc.mental.v5.feature.knockback;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import me.vexmc.mental.common.scheduling.Scheduling;
import me.vexmc.mental.common.scheduling.TaskHandle;
import me.vexmc.mental.kernel.wire.LatencyModel;
import me.vexmc.mental.v5.config.settings.CompensationSettings;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.feature.SettingsKey;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * The latency-compensation transport (the retired {@code LatencyCompensationModule}'s
 * probe half, on the v5 seams). Ping-aware compensation itself is a stateless
 * per-hit {@code CompensationQuery} the knockback family runs (spec §5); this unit
 * only feeds the {@link LatencyModel} the RTT it reads: it marks combatants on the
 * damage pass and, on a {@code repeatAsync} cadence, sends each a Play PING whose
 * PONG the always-on {@code ProbeRim} matches. KEEPALIVE support is deleted by
 * design — only the dedicated PING/PONG channel is used.
 */
public final class LatencyCompensationUnit implements FeatureUnit, Listener {

    private static final int PING_ID_MASK = 0xFFFF;

    private final LatencyModel latency;
    private final Scheduling scheduling;
    private final Supplier<Snapshot> snapshot;

    private final ConcurrentHashMap<UUID, Long> combatants = new ConcurrentHashMap<>();
    private final AtomicInteger nextPingId = new AtomicInteger();

    public LatencyCompensationUnit(LatencyModel latency, Scheduling scheduling, Supplier<Snapshot> snapshot) {
        this.latency = latency;
        this.scheduling = scheduling;
        this.snapshot = snapshot;
    }

    @Override
    public Feature descriptor() {
        return Feature.LATENCY_COMPENSATION;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        scope.listen(this);
        Duration interval = Duration.ofMillis(settings().probeIntervalTicks() * 50L);
        scope.task(() -> {
            TaskHandle handle = scheduling.repeatAsync(interval, interval, this::tickProbes);
            return () -> {
                handle.cancel();
                combatants.clear();
            };
        });
    }

    @SuppressWarnings("unchecked")
    private CompensationSettings settings() {
        return snapshot.get().settings(
                (SettingsKey<CompensationSettings>) Feature.LATENCY_COMPENSATION.settingsKey());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            return;
        }
        long now = System.currentTimeMillis();
        if (event.getEntity() instanceof Player victim) {
            combatants.put(victim.getUniqueId(), now);
        }
        if (event.getDamager() instanceof Player attacker) {
            combatants.put(attacker.getUniqueId(), now);
        }
    }

    public void forget(UUID id) {
        combatants.remove(id);
    }

    private void tickProbes() {
        long now = System.currentTimeMillis();
        long timeoutMillis = settings().combatTimeoutTicks() * 50L;
        for (UUID id : combatants.keySet()) {
            Long marked = combatants.get(id);
            Player player = Bukkit.getPlayer(id);
            if (marked == null || now - marked > timeoutMillis || player == null || !player.isOnline()) {
                combatants.remove(id);
                latency.forget(id);
                continue;
            }
            sendPing(player);
        }
    }

    private void sendPing(Player player) {
        try {
            int id = LatencyModel.PING_ID_BASE | (nextPingId.incrementAndGet() & PING_ID_MASK);
            latency.forPlayer(player.getUniqueId())
                    .onProbeSent(Integer.toUnsignedLong(id), System.nanoTime());
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, new WrapperPlayServerPing(id));
        } catch (Throwable ignored) {
            // Player vanished or sits in reconfiguration where a Play packet can't
            // encode — a dropped probe is the right outcome either way.
        }
    }
}
