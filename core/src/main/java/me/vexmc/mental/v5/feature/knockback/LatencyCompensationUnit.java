package me.vexmc.mental.v5.feature.knockback;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowConfirmation;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.platform.TaskHandle;
import me.vexmc.mental.kernel.wire.LatencyModel;
import me.vexmc.mental.v5.config.ProbeStrategy;
import me.vexmc.mental.v5.config.settings.CompensationSettings;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.feature.SettingsKey;
import me.vexmc.mental.v5.rim.ProbeTransactions;
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
 * damage pass and, on a {@code repeatAsync} cadence, sends each a probe over the
 * server's effective transport, whose echo the matching always-on rim consumes.
 *
 * <p>The transport is resolved once at boot from the version-aware
 * {@link ProbeStrategy#resolveEffective}: {@link ProbeStrategy#PING} sends a play
 * PING (matched by {@code ProbeRim}) at/above 1.17; {@link ProbeStrategy#TRANSACTION}
 * sends a windowId-0 window-confirmation (matched by {@code TransactionProbeRim})
 * below 1.17, where the play channel is absent on the wire. Only one transport is
 * ever active per server, so a single probe counter serves both. KEEPALIVE never
 * reaches here — it retired to the effective transport at resolution.</p>
 */
public final class LatencyCompensationUnit implements FeatureUnit, Listener {

    private static final int PING_ID_MASK = 0xFFFF;

    private final LatencyModel latency;
    private final Scheduling scheduling;
    private final Supplier<Snapshot> snapshot;
    private final ProbeStrategy transport;

    private final ConcurrentHashMap<UUID, Long> combatants = new ConcurrentHashMap<>();
    private final AtomicInteger nextProbeId = new AtomicInteger();

    public LatencyCompensationUnit(
            LatencyModel latency, Scheduling scheduling, Supplier<Snapshot> snapshot, ProbeStrategy transport) {
        this.latency = latency;
        this.scheduling = scheduling;
        this.snapshot = snapshot;
        this.transport = transport;
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
            sendProbe(player);
        }
    }

    /** Sends one probe over the effective transport (resolved once at boot). */
    private void sendProbe(Player player) {
        switch (transport) {
            case TRANSACTION -> sendTransaction(player);
            // PING (and any value that somehow slipped past resolution) uses the
            // modern play channel — the historical, byte-identical path.
            default -> sendPing(player);
        }
    }

    private void sendPing(Player player) {
        try {
            int id = LatencyModel.PING_ID_BASE | (nextProbeId.incrementAndGet() & PING_ID_MASK);
            latency.forPlayer(player.getUniqueId())
                    .onProbeSent(Integer.toUnsignedLong(id), System.nanoTime());
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, new WrapperPlayServerPing(id));
        } catch (Throwable ignored) {
            // Player vanished or sits in reconfiguration where a Play packet can't
            // encode — a dropped probe is the right outcome either way.
        }
    }

    private void sendTransaction(Player player) {
        try {
            short action = ProbeTransactions.action(nextProbeId.incrementAndGet());
            latency.forPlayer(player.getUniqueId())
                    .onProbeSent(ProbeTransactions.modelId(action), System.nanoTime());
            PacketEvents.getAPI().getPlayerManager()
                    .sendPacket(player, new WrapperPlayServerWindowConfirmation(0, action, false));
        } catch (Throwable ignored) {
            // Player vanished or sits in reconfiguration where the packet can't encode —
            // a dropped probe is the right outcome either way.
        }
    }

    /** The effective transport this unit sends over — the boot report / tester seam. */
    public ProbeStrategy transport() {
        return transport;
    }

    /**
     * Boot self-test seam (tester): exercise the ACTIVE transport's send path once so
     * a legacy classload/encoding break surfaces at boot rather than on the first
     * combat probe. Clientless test players carry no PacketEvents user, so the wire
     * round-trip cannot be observed here (Phase 2 gate: real-wire RTT proof is out of
     * scope) — this instead forces the transport's server wrapper to classload and
     * construct on THIS server (the real pre-1.17 send-side risk is a PE wrapper absent
     * from the wire protocol) and drives the send routine for any live player. Returns
     * true when the path completed without an escaping throwable.
     */
    public boolean probeSelfTest() {
        try {
            switch (transport) {
                case TRANSACTION -> new WrapperPlayServerWindowConfirmation(0, ProbeTransactions.action(1), false);
                default -> new WrapperPlayServerPing(LatencyModel.PING_ID_BASE);
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                sendProbe(player);
            }
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }
}
