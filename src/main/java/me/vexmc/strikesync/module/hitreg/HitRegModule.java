package me.vexmc.strikesync.module.hitreg;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import me.vexmc.strikesync.core.Module;
import me.vexmc.strikesync.core.StrikeSyncService;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Owns the PacketEvents {@code INTERACT_ENTITY} listener and the per-player
 * CPS state.
 *
 * <p>
 * Designed for hot-reload: {@link #reload()} just inspects the latest
 * {@code HitRegSettings} and starts/stops the underlying PacketEvents listener
 * without ever creating duplicates.
 */
public final class HitRegModule implements Module, Listener {

	private final StrikeSyncService service;
	private final CpsLimiter limiter = new CpsLimiter();

	private HitPacketListener listener;
	private PacketListenerCommon handle;
	private boolean enabledByConfig;
	private boolean registeredAsListener;

	public HitRegModule(StrikeSyncService service) {
		this.service = service;
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
		applyEnabledState(service.config().hitReg().enabled());
	}

	@Override
	public void disable() {
		applyEnabledState(false);
		limiter.clear();
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

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		limiter.forget(event.getPlayer().getUniqueId());
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
		listener = new HitPacketListener(service, limiter);
		handle = PacketEvents.getAPI().getEventManager().registerListener(
				listener, PacketListenerPriority.NORMAL);
		service.log().info("Async hit registration started.");
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
}
