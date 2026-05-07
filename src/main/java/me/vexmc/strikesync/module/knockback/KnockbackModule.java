package me.vexmc.strikesync.module.knockback;

import me.vexmc.strikesync.config.KnockbackSettings;
import me.vexmc.strikesync.core.Module;
import me.vexmc.strikesync.core.StrikeSyncService;
import me.vexmc.strikesync.module.compensation.LatencyCompensationModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerVelocityEvent;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom 1.8.x knockback module.
 *
 * <h2>Why we don't run the math asynchronously</h2>
 * The previous implementation offloaded the calculation to a thread pool and
 * stored the result for the next {@link PlayerVelocityEvent}. That race is
 * exactly what produced the legendary "5x vertical knockback" bug: the
 * velocity event fired before the async calculation stored its result, so
 * vanilla velocity stayed; then a moment later the next tick's velocity event
 * picked up our stale calculation and applied it on top. The math is cheap, so
 * we run it inline on the main thread and stash the result before
 * {@link PlayerVelocityEvent} fires (which always happens later in the same
 * tick).
 *
 * <h2>Composition with latency compensation</h2>
 * If the {@link LatencyCompensationModule} is enabled, it has already produced
 * a {@code Hint} with a client-expected victim vy by the time we run on
 * {@link EventPriority#MONITOR}. We feed that into
 * {@link KnockbackEngine#compute} as the optional {@code victimYOverride}
 * parameter — so the engine remains the single source of truth for the
 * knockback formula, while the compensator only corrects the input.
 */
public final class KnockbackModule implements Module, Listener {

	private final StrikeSyncService service;
	private final ConcurrentHashMap<UUID, KnockbackVector> pending = new ConcurrentHashMap<>();
	private boolean registered;

	public KnockbackModule(StrikeSyncService service) {
		this.service = service;
	}

	@Override
	public String id() {
		return "knockback";
	}

	@Override
	public void enable() {
		if (!registered) {
			Bukkit.getPluginManager().registerEvents(this, service.plugin());
			registered = true;
		}
	}

	@Override
	public void disable() {
		if (registered) {
			HandlerList.unregisterAll(this);
			registered = false;
		}
		pending.clear();
	}

	@Override
	public void reload() {
		// Settings are read live from the snapshot — nothing to do.
	}

	public boolean isEnabled() {
		return service.config().knockback().enabled();
	}

	/* ----------------------------- listeners ----------------------------- */

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		pending.remove(event.getPlayer().getUniqueId());
	}

	/**
	 * Compute custom knockback after vanilla picks the damage path. MONITOR
	 * priority: we don't influence cancellation; we only observe the final
	 * outcome and stash a vector for the velocity event.
	 *
	 * <p>
	 * Runs <em>after</em> {@link LatencyCompensationModule} which is at
	 * HIGHEST, so any compensation hint is ready when we compute.
	 */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
		KnockbackSettings s = service.config().knockback();
		if (!s.enabled())
			return;
		if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK)
			return;
		if (!(event.getDamager() instanceof LivingEntity attacker))
			return;
		if (!(event.getEntity() instanceof Player victim))
			return;

		Double victimYOverride = readCompensationOverride(victim.getUniqueId());

		KnockbackVector vector = KnockbackEngine.compute(attacker, victim, s, victimYOverride);
		pending.put(victim.getUniqueId(), vector);
		service.log().debug(() -> "queued KB for " + victim.getName()
				+ (victimYOverride != null ? " [compensated vy=" + round(victimYOverride) + "]" : "")
				+ " = (" + round(vector.x()) + "," + round(vector.y()) + "," + round(vector.z()) + ")");
	}

	/**
	 * Apply our stored knockback vector. HIGH priority lets other plugins
	 * inspect and cancel before us; we still respect cancellation.
	 */
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onPlayerVelocity(PlayerVelocityEvent event) {
		if (!service.config().knockback().enabled())
			return;
		KnockbackVector vector = pending.remove(event.getPlayer().getUniqueId());
		if (vector == null)
			return;
		event.setVelocity(vector.toBukkit());
		service.log().debug(() -> "applied KB to " + event.getPlayer().getName());
	}

	private Double readCompensationOverride(UUID victim) {
		LatencyCompensationModule comp = service.lookup(LatencyCompensationModule.class);
		if (comp == null)
			return null;
		LatencyCompensationModule.Hint hint = comp.takeHint(victim);
		return hint != null ? hint.victimYOverride() : null;
	}

	private static double round(double v) {
		return Math.round(v * 100.0D) / 100.0D;
	}
}
