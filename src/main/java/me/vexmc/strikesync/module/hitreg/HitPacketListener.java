package me.vexmc.strikesync.module.hitreg;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity.InteractAction;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import me.vexmc.strikesync.api.event.AsyncHitRegisterEvent;
import me.vexmc.strikesync.config.HitRegSettings;
import me.vexmc.strikesync.core.StrikeSyncService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * The PacketEvents listener that turns {@code INTERACT_ENTITY} packets into
 * {@link AsyncHitRegisterEvent}s and enforces the configured CPS cap.
 *
 * <h2>Why we don't override damage application</h2>
 * Re-implementing damage in the plugin (cancelling the packet, then calling
 * {@code livingTarget.damage(...)} on the next tick) re-fires
 * {@code EntityDamageByEntityEvent}, breaks weapon attribute calculations,
 * and routinely double-hits. Vanilla already applies damage on the next tick;
 * the goal of an async listener is to <em>filter</em> hits and to fire our
 * cancellable event before vanilla sees the packet — not to take over hit
 * processing.
 *
 * <p>
 * So this listener:
 * <ol>
 * <li>Reads the packet on PacketEvents' netty event loop,</li>
 * <li>Differentiates ATTACK from INTERACT via {@link InteractAction},</li>
 * <li>Rate-limits via {@link CpsLimiter},</li>
 * <li>Fires {@link AsyncHitRegisterEvent} (genuinely async),</li>
 * <li>Cancels the packet only if rate-limited, validation failed, or the
 * event was cancelled,</li>
 * <li>Otherwise lets vanilla apply the hit normally.</li>
 * </ol>
 */
final class HitPacketListener implements PacketListener {

	private final StrikeSyncService service;
	private final CpsLimiter limiter;

	HitPacketListener(StrikeSyncService service, CpsLimiter limiter) {
		this.service = service;
		this.limiter = limiter;
	}

	@Override
	public void onPacketReceive(PacketReceiveEvent event) {
		if (event.isCancelled())
			return;
		if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY)
			return;

		Player attacker = (Player) event.getPlayer();
		if (attacker == null)
			return;

		try {
			WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
			if (packet.getAction() != InteractAction.ATTACK) {
				return; // INTERACT / INTERACT_AT — let vanilla handle.
			}

			HitRegSettings settings = service.config().hitReg();
			if (!settings.enabled())
				return;

			if (settings.rateLimited()
					&& !limiter.tryAcquire(attacker.getUniqueId(),
							settings.maxCps(),
							System.currentTimeMillis())) {
				event.setCancelled(true);
				debug(attacker, () -> "rate-limited at " + settings.maxCps() + " cps");
				return;
			}

			int targetEntityId = packet.getEntityId();
			if (targetEntityId < 0)
				return;

			Entity target = SpigotConversionUtil.getEntityById(attacker.getWorld(), targetEntityId);
			if (!(target instanceof Damageable damageable))
				return;
			if (damageable.isDead())
				return;

			if (!isAttackable(attacker, damageable))
				return;

			AsyncHitRegisterEvent api = new AsyncHitRegisterEvent(attacker, damageable);
			Bukkit.getPluginManager().callEvent(api);
			if (api.isCancelled()) {
				event.setCancelled(true);
				debug(attacker, () -> "cancelled by AsyncHitRegisterEvent listener");
			} else {
				debug(attacker, () -> "passed through to vanilla on " + describe(damageable));
			}
		} catch (Throwable t) {
			// Never throw from a packet handler — log and let vanilla handle.
			service.log().warn("Error in HitPacketListener; allowing packet through", t);
		}
	}

	/* ----------------------------- helpers ----------------------------- */

	private static boolean isAttackable(Player attacker, Damageable target) {
		if (attacker.getWorld() != target.getWorld())
			return false;
		if (target instanceof Player && !pvpEnabled(attacker))
			return false;
		if (target instanceof Player tp && tp.getGameMode() == GameMode.CREATIVE)
			return false;
		return attacker.getGameMode() != GameMode.SPECTATOR;
	}

	private static boolean pvpEnabled(Player attacker) {
		Boolean pvp = attacker.getWorld().getGameRuleValue(GameRules.PVP);
		return pvp == null || pvp; // game rule defaults to true; treat null as enabled.
	}

	private static String describe(Damageable target) {
		if (target instanceof Player p)
			return p.getName();
		return target.getType().toString().toLowerCase();
	}

	private void debug(Player attacker, Supplier<String> message) {
		service.log().debug(() -> "hitreg[" + attacker.getName() + "] " + message.get());
	}

	/** Visible for unit-style tests if anyone ever wires them up. */
	static UUID idOf(Player p) {
		return p.getUniqueId();
	}
}
