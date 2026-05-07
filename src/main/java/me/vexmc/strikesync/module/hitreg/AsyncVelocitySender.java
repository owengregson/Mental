package me.vexmc.strikesync.module.hitreg;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Sends a {@code WrapperPlayServerEntityVelocity} packet to a recipient,
 * thread-safe to invoke from PacketEvents' netty event loop.
 *
 * <h2>Why this is the headline latency win</h2>
 * Vanilla's outbound velocity packet is bound to the entity-tracker pulse,
 * which on default Paper is every 2 ticks for players. That means the victim
 * normally sees their knockback {@code (next-tick + tracker-pulse)} milliseconds
 * after the click hits the server — ~50–100ms of dead time.
 *
 * <p>Sending the velocity packet directly from the netty thread skips both
 * the next-tick wait <em>and</em> the tracker pulse. The victim sees the
 * knockback at {@code T + RTT} instead of {@code T + RTT + (50–100 ms)}.
 *
 * <p>The damage application still runs on the main thread shortly after; if
 * its computed velocity differs from the async-sent one, vanilla's tracker
 * pulses a second velocity packet on the next tick, which the client treats
 * as a small correction. For low-jitter situations the two packets carry the
 * same value and the second is a visual no-op.
 */
public final class AsyncVelocitySender {

    private AsyncVelocitySender() {}

    /**
     * Send a velocity packet to {@code recipient} for the entity with id
     * {@code entityId}. Safe to call from any thread; PacketEvents' player
     * manager performs the channel write through netty's own event loop.
     */
    public static void send(Player recipient, int entityId, Vector velocity) {
        WrapperPlayServerEntityVelocity packet = new WrapperPlayServerEntityVelocity(
                entityId,
                new Vector3d(velocity.getX(), velocity.getY(), velocity.getZ()));
        PacketEvents.getAPI().getPlayerManager().sendPacket(recipient, packet);
    }
}
