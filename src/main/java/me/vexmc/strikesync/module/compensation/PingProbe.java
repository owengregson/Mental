package me.vexmc.strikesync.module.compensation;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientKeepAlive;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerKeepAlive;
import org.bukkit.entity.Player;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Sends outbound {@code KEEP_ALIVE} packets to combat-tagged players and
 * intercepts the matching inbound packets to compute round-trip time.
 *
 * <p>
 * We use KEEP_ALIVE specifically because:
 * <ul>
 * <li>It already round-trips on every vanilla install — clients respond
 * quickly (or get disconnected, which the server handles).</li>
 * <li>The id is a 64-bit long, so we can pick a high starting offset
 * ({@link LatencyTracker#PROBE_ID_BASE}) that vanilla is unlikely to
 * collide with at our exact id.</li>
 * <li>It doesn't change game state — it's a heartbeat.</li>
 * </ul>
 *
 * <p>
 * We listen for inbound KEEP_ALIVE on PacketEvents' netty event loop,
 * cancel the packet when the id matches one of ours, and let vanilla handle
 * everything else. The {@link LatencyTracker} match is exact (and only mutates
 * state on a hit), so a vanilla keep-alive can't disturb our outstanding map
 * even if it shares an id range.
 */
final class PingProbe implements PacketListener {

	private final LatencyTracker tracker;
	private final AtomicLong nextId = new AtomicLong(LatencyTracker.PROBE_ID_BASE);

	PingProbe(LatencyTracker tracker) {
		this.tracker = tracker;
	}

	/** Send a KEEP_ALIVE probe to {@code player}, returning the id we used. */
	long send(Player player) {
		long id = nextId.incrementAndGet();
		try {
			WrapperPlayServerKeepAlive packet = new WrapperPlayServerKeepAlive(id);
			tracker.forPlayer(player.getUniqueId()).onProbeSent(id, System.nanoTime());
			PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
		} catch (Throwable t) {
			// Player disconnected between selection and send — silently drop.
		}
		return id;
	}

	@Override
	public void onPacketReceive(PacketReceiveEvent event) {
		if (event.getPacketType() != PacketType.Play.Client.KEEP_ALIVE)
			return;
		if (event.getPlayer() == null)
			return;
		try {
			long id = new WrapperPlayClientKeepAlive(event).getId();
			// onResponse only mutates state when the id matches one of ours,
			// so it's safe to call for every incoming KEEP_ALIVE — vanilla's
			// ids simply return false and we let the packet flow through.
			Player player = (Player) event.getPlayer();
			boolean wasOurs = tracker.forPlayer(player.getUniqueId())
					.onResponse(id, System.nanoTime());
			if (wasOurs) {
				event.setCancelled(true); // suppress vanilla's mismatched-id disconnect path
			}
		} catch (Throwable ignored) {
			// Never throw from a packet handler.
		}
	}
}
