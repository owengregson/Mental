package me.vexmc.strikesync.module.compensation;

import me.vexmc.strikesync.config.CompensationSettings;
import me.vexmc.strikesync.core.Module;
import me.vexmc.strikesync.core.StrikeSyncService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Latency-compensation module — designed to <strong>compose</strong> with
 * StrikeSync's 1.8-style {@code KnockbackModule}, not to override its output.
 *
 * <h2>Why this is not a knockback rewriter</h2>
 * KnockbackSync (the upstream we draw the algorithms from) was built for
 * vanilla 1.9+ knockback and uses constants such as {@code 0.36080000519752503}
 * and a knockback-resistance Y-subtraction step that are <em>specific to that
 * formula</em>. StrikeSync's engine implements 1.8.x knockback with
 * configurable friction; if we'd let those 1.9+ constants overwrite the engine
 * output, we'd be silently undoing every server's tuned 1.8 feel.
 *
 * <p>
 * So this module's only job is to detect <em>"the victim's vy on the
 * server is stale due to latency"</em> and publish a hint with the
 * client-expected vy. The {@code KnockbackModule} reads the hint at
 * {@code MONITOR} priority on {@link EntityDamageByEntityEvent} and feeds it
 * into {@code KnockbackEngine.compute(..., victimYOverride)}. The engine still
 * owns the output formula; we just correct the input.
 *
 * <h2>How the override is computed</h2>
 * <ol>
 * <li>If {@link Player#getVelocity()}.y is already 0 we leave it alone — server
 * agrees with client.</li>
 * <li>4-corner raytrace measures distance-to-ground.</li>
 * <li>{@link MotionMath} simulates flight from the current vy under vanilla
 * gravity for {@code compensatedTicks} ticks.</li>
 * <li>If the result lands on the ground within those ticks, the client
 * thought the victim was on the ground at hit time → override = 0
 * (engine produces full ground-hit vertical = {@code base.vertical}).</li>
 * <li>Otherwise (and {@code off-ground-sync} is enabled), the override is
 * the simulated current vy — closer to what the client sees than the
 * server's stale value.</li>
 * </ol>
 */
public final class LatencyCompensationModule implements Module, Listener {

	private static final double DEFAULT_GRAVITY = 0.08D;
	private static final long HINT_TTL_MS = 250L;

	private final StrikeSyncService service;
	private final LatencyTracker tracker = new LatencyTracker();
	private final CombatTracker combat = new CombatTracker();
	private final ConcurrentHashMap<UUID, Hint> hints = new ConcurrentHashMap<>();

	private PingProbe probe;
	private com.github.retrooper.packetevents.event.PacketListenerCommon probeHandle;
	private BukkitTask probeTask;
	private boolean registeredAsListener;

	public LatencyCompensationModule(StrikeSyncService service) {
		this.service = service;
	}

	@Override
	public String id() {
		return "compensation";
	}

	@Override
	public void enable() {
		service.publish(LatencyCompensationModule.class, this);
		if (!registeredAsListener) {
			Bukkit.getPluginManager().registerEvents(this, service.plugin());
			registeredAsListener = true;
		}
		applyEnabled(service.config().compensation().enabled());
	}

	@Override
	public void disable() {
		applyEnabled(false);
		if (registeredAsListener) {
			HandlerList.unregisterAll(this);
			registeredAsListener = false;
		}
		tracker.clear();
		combat.clear();
		hints.clear();
		service.publish(LatencyCompensationModule.class, null);
	}

	@Override
	public void reload() {
		applyEnabled(service.config().compensation().enabled());
	}

	public boolean isEnabled() {
		return service.config().compensation().enabled() && probe != null;
	}

	public Snapshot pingSnapshot(UUID player) {
		LatencyTracker.Record record = tracker.forPlayer(player);
		return new Snapshot(record.pingMs(), record.previousPingMs(), record.jitterMs(),
				isSpike(record));
	}

	/**
	 * Read a freshly-computed compensation hint for a hit on {@code victim}.
	 * Returns {@code null} when no hit hint is current (TTL expired) or when
	 * the module was unable to compute a meaningful override.
	 *
	 * <p>
	 * Called by {@code KnockbackModule} from the main thread; safe to call
	 * any number of times — the hint is consumed and removed on first read so
	 * a stale value from an earlier tick can't bleed into the next hit.
	 */
	public Hint takeHint(UUID victim) {
		Hint h = hints.remove(victim);
		if (h == null)
			return null;
		if (System.currentTimeMillis() - h.computedAtMs > HINT_TTL_MS)
			return null;
		return h;
	}

	/* ----------------------------- listeners ----------------------------- */

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		UUID id = event.getPlayer().getUniqueId();
		tracker.forget(id);
		combat.forget(id);
		hints.remove(id);
	}

	/**
	 * Compute the latency hint <em>before</em> KnockbackModule's MONITOR
	 * listener runs. EventPriority order: HIGHEST runs before MONITOR.
	 */
	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onDamage(EntityDamageByEntityEvent event) {
		if (!service.config().compensation().enabled())
			return;
		if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK)
			return;
		if (!(event.getEntity() instanceof Player victim))
			return;
		if (victim.getGameMode() == GameMode.CREATIVE
				|| victim.getGameMode() == GameMode.SPECTATOR)
			return;

		UUID victimId = victim.getUniqueId();
		long now = System.currentTimeMillis();
		combat.mark(victimId, now);
		if (event.getDamager() instanceof Player attacker) {
			combat.mark(attacker.getUniqueId(), now);
		}

		CompensationSettings settings = service.config().compensation();
		Double pingMs = tracker.forPlayer(victimId).pingMs();
		if (pingMs == null)
			return;

		double compensated = compensatedPingMs(victimId, settings, pingMs);
		if (compensated <= 0)
			return;

		Hint hint = computeHint(victim, settings, compensated);
		if (hint != null) {
			hints.put(victimId, hint);
			service.log().debug(() -> "compensation hint for " + victim.getName()
					+ " ping=" + round(pingMs) + "ms compensated=" + round(compensated) + "ms"
					+ " override=" + round(hint.victimYOverride));
		}
	}

	/* ----------------------------- internals ----------------------------- */

	private void applyEnabled(boolean shouldEnable) {
		if (shouldEnable && probe == null)
			startProbing();
		else if (!shouldEnable && probe != null)
			stopProbing();
	}

	private void startProbing() {
		probe = new PingProbe(tracker);
		probeHandle = com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager()
				.registerListener(probe, com.github.retrooper.packetevents.event.PacketListenerPriority.LOWEST);

		long interval = service.config().compensation().probeIntervalTicks();
		probeTask = Bukkit.getScheduler().runTaskTimerAsynchronously(service.plugin(),
				this::tickProbes, interval, interval);
		service.log().info("Latency compensation started.");
	}

	private void stopProbing() {
		try {
			if (probeTask != null) {
				probeTask.cancel();
				probeTask = null;
			}
			if (probeHandle != null) {
				com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager()
						.unregisterListener(probeHandle);
				probeHandle = null;
			}
			probe = null;
		} finally {
			service.log().info("Latency compensation stopped.");
		}
	}

	private void tickProbes() {
		if (probe == null)
			return;
		long nowMs = System.currentTimeMillis();
		long timeoutMs = service.config().compensation().combatTimeoutTicks() * 50L;
		combat.evictExpired(nowMs, timeoutMs);
		for (UUID id : combat.snapshot()) {
			Player player = Bukkit.getPlayer(id);
			if (player == null || !player.isOnline()) {
				combat.forget(id);
				tracker.forget(id);
				continue;
			}
			probe.send(player);
		}
	}

	private double compensatedPingMs(UUID victimId, CompensationSettings settings, double pingMs) {
		LatencyTracker.Record r = tracker.forPlayer(victimId);
		Double previous = r.previousPingMs();
		double effective = pingMs;
		if (previous != null && (pingMs - previous) > settings.spikeThresholdMillis()) {
			effective = previous; // ignore one-shot spikes
		}
		return Math.max(0.0D, effective - settings.pingOffsetMillis());
	}

	/**
	 * @return a hint with {@code victimYOverride} set to the value the
	 *         {@code KnockbackEngine} should use as the victim's vertical
	 *         velocity, or {@code null} when no compensation applies.
	 */
	private Hint computeHint(Player victim, CompensationSettings settings, double compensatedMs) {
		Vector velocity = victim.getVelocity();
		double serverVy = velocity.getY();
		double gravity = readGravity(victim);
		int compensatedTicks = (int) Math.ceil(compensatedMs * 20.0D / 1000.0D);
		if (compensatedTicks <= 0)
			return null;

		double distance = GroundProbe.distanceToGround(victim);
		if (distance <= 0) {
			// Server already thinks the victim is on the ground — override = 0
			// is the same as the server's truth, so no work needed.
			return null;
		}

		if (predictClientOnGround(serverVy, gravity, compensatedTicks, distance)) {
			int noDamage = victim.getNoDamageTicks();
			if (noDamage > 8)
				return null; // double-hit guard borrowed from KnockbackSync.
			return new Hint(0.0D, true, System.currentTimeMillis());
		}

		if (settings.offGroundSync()) {
			// Forward-simulate the server's last-known vy to get the client's
			// current vy. Imperfect when the player has applied input since
			// the last server snapshot, but better than the stale value.
			double simulatedVy = MotionMath.simulateVerticalVelocity(serverVy, gravity, compensatedTicks);
			return new Hint(simulatedVy, false, System.currentTimeMillis());
		}

		return null;
	}

	private static boolean predictClientOnGround(double yVelocity, double gravity,
			int compensatedTicks, double distanceToGround) {
		if (distanceToGround > 1.3D)
			return false;
		int tApex = yVelocity > 0 ? MotionMath.ticksToApex(yVelocity, gravity) : 0;
		if (tApex == -1)
			return false;
		double apexHeight = yVelocity > 0
				? MotionMath.distanceTraveled(yVelocity, tApex, gravity)
				: 0.0D;
		int tFall = MotionMath.ticksToFall(yVelocity, apexHeight + distanceToGround, gravity);
		if (tFall == -1)
			return false;
		return (tApex + tFall) - compensatedTicks <= 0;
	}

	private static double readGravity(Player p) {
		AttributeInstance attr = p.getAttribute(Attribute.GRAVITY);
		return attr != null ? attr.getValue() : DEFAULT_GRAVITY;
	}

	private static boolean isSpike(LatencyTracker.Record r) {
		Double p = r.pingMs();
		Double prev = r.previousPingMs();
		return p != null && prev != null && (p - prev) > 20.0D;
	}

	private static double round(double v) {
		return Math.round(v * 100.0D) / 100.0D;
	}

	/**
	 * Compensation hint for a single hit. {@code victimYOverride} is the value
	 * to use in place of the server's victim Y velocity in the knockback
	 * calculation.
	 */
	public record Hint(double victimYOverride, boolean clientOnGround, long computedAtMs) {
	}

	/** Read-only snapshot returned to the {@code /ss ping} command. */
	public record Snapshot(Double pingMs, Double previousPingMs,
			double jitterMs, boolean spike) {
	}
}
