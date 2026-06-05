package me.vexmc.mental.module.compensation;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.common.scheduling.TaskHandle;
import me.vexmc.mental.config.CompensationSettings;
import me.vexmc.mental.engine.CombatModule;
import me.vexmc.mental.module.knockback.KnockbackHints;
import me.vexmc.mental.module.knockback.VictimMotion;
import me.vexmc.mental.platform.Attributes;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Ping-aware vertical-knockback compensation.
 *
 * <p>This module never rewrites knockback itself — it detects "the victim's
 * vertical motion on the server is stale by one network leg" and publishes a
 * one-shot hint with the client-expected value. The knockback engine stays
 * the single owner of the formula; the compensator only corrects its input
 * (the KnockbackSync-derived algorithm, adapted for Mental's legacy engine).</p>
 *
 * <p>Pipeline per hit, at {@code HIGHEST} damage priority (one slot before
 * the knockback module's {@code MONITOR} pass): spike-filter the measured
 * RTT, subtract the tournament-derived offset, convert to ticks, raytrace
 * distance-to-ground, then simulate vanilla gravity forward. If the victim's
 * client must already have landed, the hint zeroes vertical motion (full
 * base knockback); otherwise, with off-ground sync on, the hint carries the
 * forward-simulated velocity.</p>
 */
public final class LatencyCompensationModule extends CombatModule implements Listener, KnockbackHints {

    private static final double DEFAULT_GRAVITY = 0.08;
    private static final long HINT_TTL_MILLIS = 300L; // outlives one probe interval (5 ticks)
    private static final double SPIKE_DISPLAY_THRESHOLD_MILLIS = 20.0;

    /** One-shot hint: the Y value the engine should treat as the victim's motion. */
    public record Hint(double victimYOverride, boolean clientOnGround, long computedAtMillis) {}

    /** Read-only ping statistics for the command layer. */
    public record PingStats(
            @Nullable Double pingMillis,
            @Nullable Double previousPingMillis,
            double jitterMillis,
            boolean spike) {}

    private final LatencyTracker tracker = new LatencyTracker();
    private final CombatTracker combat = new CombatTracker();
    private final ConcurrentHashMap<UUID, Hint> hints = new ConcurrentHashMap<>();
    private final VictimMotion ledger;

    private ProbeListener probe;
    private PacketListenerCommon probeHandle;
    private TaskHandle probeTask;

    public LatencyCompensationModule(@NotNull MentalServices services, @NotNull VictimMotion ledger) {
        super(services, "latency-compensation", "Latency Compensation",
                "Ping-aware vertical knockback correction with spike-filtered probes.",
                DebugCategory.COMPENSATION);
        this.ledger = ledger;
    }

    @Override
    public boolean configEnabled() {
        return services.config().compensation().enabled();
    }

    @Override
    protected void onEnable() {
        listen(this);
        CompensationSettings settings = services.config().compensation();
        probe = new ProbeListener(tracker, settings.probeStrategy());
        probeHandle = PacketEvents.getAPI().getEventManager()
                .registerListener(probe, PacketListenerPriority.LOWEST);
        startProbeTask(settings);
        debug.log(() -> "probing via " + settings.probeStrategy()
                + " every " + settings.probeIntervalTicks() + " ticks");
    }

    @Override
    protected void onDisable() {
        if (probeTask != null) {
            probeTask.cancel();
            probeTask = null;
        }
        if (probeHandle != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(probeHandle);
            probeHandle = null;
        }
        probe = null;
        tracker.clear();
        combat.clear();
        hints.clear();
    }

    @Override
    protected void onReload() {
        CompensationSettings settings = services.config().compensation();
        if (probe != null) {
            probe.strategy(settings.probeStrategy());
        }
        if (probeTask != null) {
            probeTask.cancel();
        }
        startProbeTask(settings);
    }

    @Override
    public @Nullable Double takeYOverride(@NotNull UUID victimId) {
        Hint hint = hints.remove(victimId);
        if (hint == null || System.currentTimeMillis() - hint.computedAtMillis() > HINT_TTL_MILLIS) {
            return null;
        }
        return hint.victimYOverride();
    }

    public @NotNull PingStats pingStats(@NotNull UUID player) {
        LatencyTracker.Record record = tracker.forPlayer(player);
        Double ping = record.pingMillis();
        Double previous = record.previousPingMillis();
        boolean spike = ping != null && previous != null
                && (ping - previous) > SPIKE_DISPLAY_THRESHOLD_MILLIS;
        return new PingStats(ping, previous, record.jitterMillis(), spike);
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        tracker.forget(id);
        combat.forget(id);
        hints.remove(id);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(@NotNull EntityDamageByEntityEvent event) {
        CompensationSettings settings = services.config().compensation();
        if (!settings.enabled()
                || event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || !(event.getEntity() instanceof Player victim)
                || victim.getGameMode() == GameMode.CREATIVE
                || victim.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        // Combat marking only — the hints themselves are published per probe
        // tick (see tickProbes), because under the fast path the netty
        // pre-send consumes them BEFORE any damage event runs.
        long now = System.currentTimeMillis();
        combat.mark(victim.getUniqueId(), now);
        if (event.getDamager() instanceof Player attacker) {
            combat.mark(attacker.getUniqueId(), now);
        }
    }

    /** Recomputes and publishes a victim's hint; runs on the victim's owning thread. */
    private void publishHint(@NotNull Player victim) {
        CompensationSettings settings = services.config().compensation();
        UUID victimId = victim.getUniqueId();
        Double pingMillis = tracker.forPlayer(victimId).pingMillis();
        if (pingMillis == null) {
            return;
        }
        double compensated = compensatedPingMillis(victimId, settings, pingMillis);
        if (compensated <= 0) {
            return;
        }
        Hint hint = computeHint(victim, settings, compensated);
        if (hint != null) {
            hints.put(victimId, hint);
            debug.log(() -> "hint for " + victim.getName()
                    + " ping=" + pingMillis + "ms compensated=" + compensated + "ms"
                    + " override=" + hint.victimYOverride()
                    + (hint.clientOnGround() ? " (client on ground)" : ""));
        }
    }

    private void startProbeTask(CompensationSettings settings) {
        Duration interval = Duration.ofMillis(settings.probeIntervalTicks() * 50L);
        probeTask = services.scheduling().repeatAsync(interval, interval, this::tickProbes);
    }

    private void tickProbes() {
        ProbeListener currentProbe = probe;
        if (currentProbe == null) {
            return;
        }
        long now = System.currentTimeMillis();
        combat.evictExpired(now, services.config().compensation().combatTimeoutTicks() * 50L);
        for (UUID id : combat.snapshot()) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                combat.forget(id);
                tracker.forget(id);
                continue;
            }
            currentProbe.send(player);
            // Hint publication rides the probe cadence (TTL outlives one
            // interval); the raytrace and ledger read need the owning thread.
            services.scheduling().runOn(player, () -> publishHint(player), () -> {});
        }
    }

    private double compensatedPingMillis(UUID victimId, CompensationSettings settings, double pingMillis) {
        Double previous = tracker.forPlayer(victimId).previousPingMillis();
        double effective = pingMillis;
        if (previous != null && (pingMillis - previous) > settings.spikeThresholdMillis()) {
            effective = previous;
        }
        return Math.max(0.0, effective - settings.pingOffsetMillis());
    }

    @SuppressWarnings("deprecation") // Entity#isOnGround: client-reported, feeds the residual decay
    private @Nullable Hint computeHint(Player victim, CompensationSettings settings, double compensatedMillis) {
        double gravity = Attributes.valueOr(victim, Attributes.gravity(), DEFAULT_GRAVITY);
        // The residual ledger is the same motion model the knockback engine
        // consumes — the hint extrapolates that exact value forward by ping.
        double serverVy = ledger.current(
                victim.getUniqueId(), System.nanoTime(), victim.isOnGround(), gravity).vy();
        int compensatedTicks = (int) Math.ceil(compensatedMillis * 20.0 / 1000.0);
        if (compensatedTicks <= 0) {
            return null;
        }

        double distance = GroundProbe.distanceToGround(victim);
        if (distance <= 0) {
            return null; // server already sees the victim grounded — nothing stale to fix
        }

        if (predictClientOnGround(serverVy, gravity, compensatedTicks, distance)) {
            if (victim.getNoDamageTicks() > 8) {
                return null; // double-hit guard (KnockbackSync heritage)
            }
            // The value the era server's fields would hold once it learned of
            // the landing: the grounded equilibrium, never zero — a zero here
            // re-inflated combo verticals by the missing −0.0784/2 (measured).
            return new Hint(VictimMotion.groundedEquilibrium(gravity), true, System.currentTimeMillis());
        }

        if (settings.offGroundSync()) {
            double simulatedVy = MotionMath.simulateVerticalVelocity(serverVy, gravity, compensatedTicks);
            return new Hint(simulatedVy, false, System.currentTimeMillis());
        }
        return null;
    }

    private static boolean predictClientOnGround(
            double yVelocity, double gravity, int compensatedTicks, double distanceToGround) {
        if (distanceToGround > 1.3) {
            return false;
        }
        int ticksToApex = yVelocity > 0 ? MotionMath.ticksToApex(yVelocity, gravity) : 0;
        if (ticksToApex == -1) {
            return false;
        }
        double apexHeight = yVelocity > 0
                ? MotionMath.distanceTraveled(yVelocity, ticksToApex, gravity)
                : 0.0;
        int ticksToFall = MotionMath.ticksToFall(yVelocity, apexHeight + distanceToGround, gravity);
        if (ticksToFall == -1) {
            return false;
        }
        return (ticksToApex + ticksToFall) - compensatedTicks <= 0;
    }
}
