package me.vexmc.mental.module.compensation;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientKeepAlive;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerKeepAlive;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import me.vexmc.mental.config.ProbeStrategy;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Sends latency probes and intercepts their responses.
 *
 * <p>{@link ProbeStrategy#PING} rides the dedicated play ping/pong channel
 * (1.17+ — exactly Mental's floor): purpose-built for round-trips, no
 * disconnect semantics, and a private id space far from the small ids
 * anticheats use for their own transactions. {@link ProbeStrategy#KEEPALIVE}
 * measures over keep-alive packets, answering Mental's own probes before
 * vanilla's mismatched-id disconnect path can see them.</p>
 *
 * <p>Responses are matched exactly against the outstanding set, so foreign
 * pongs and vanilla keep-alives flow through untouched; only Mental's own
 * responses are cancelled.</p>
 */
final class ProbeListener implements PacketListener {

    private static final int PING_ID_MASK = 0xFFFF;

    private final LatencyTracker tracker;
    private final AtomicLong nextKeepAliveId = new AtomicLong(LatencyTracker.KEEPALIVE_ID_BASE);
    private final AtomicInteger nextPingId = new AtomicInteger();
    private volatile ProbeStrategy strategy;

    ProbeListener(@NotNull LatencyTracker tracker, @NotNull ProbeStrategy strategy) {
        this.tracker = tracker;
        this.strategy = strategy;
    }

    void strategy(@NotNull ProbeStrategy strategy) {
        this.strategy = strategy;
    }

    void send(@NotNull Player player) {
        try {
            long id;
            Object packet;
            if (strategy == ProbeStrategy.PING) {
                id = LatencyTracker.PING_ID_BASE | (nextPingId.incrementAndGet() & PING_ID_MASK);
                packet = new WrapperPlayServerPing((int) id);
            } else {
                id = nextKeepAliveId.incrementAndGet();
                packet = new WrapperPlayServerKeepAlive(id);
            }
            tracker.forPlayer(player.getUniqueId()).onProbeSent(id, System.nanoTime());
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    (com.github.retrooper.packetevents.wrapper.PacketWrapper<?>) packet);
        } catch (Throwable disconnected) {
            // Player vanished between selection and send — drop silently.
        }
    }

    @Override
    public void onPacketReceive(@NotNull PacketReceiveEvent event) {
        PacketType.Play.Client type = (PacketType.Play.Client) event.getPacketType();
        if (type != PacketType.Play.Client.KEEP_ALIVE && type != PacketType.Play.Client.PONG) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        try {
            long id = type == PacketType.Play.Client.KEEP_ALIVE
                    ? new WrapperPlayClientKeepAlive(event).getId()
                    : new WrapperPlayClientPong(event).getId();
            boolean wasOurs = tracker.forPlayer(player.getUniqueId()).onResponse(id, System.nanoTime());
            if (wasOurs) {
                event.setCancelled(true);
            }
        } catch (Throwable ignored) {
            // Never throw from a packet handler.
        }
    }
}
