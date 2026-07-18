package me.vexmc.mental.v5.rim;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import java.util.UUID;
import me.vexmc.mental.kernel.wire.LatencyModel;

/**
 * The latency probe receive side (spec §5): it matches inbound Play PONG ids
 * against the outstanding probes the latency-compensation transport sent, so a
 * matched response updates the RTT/jitter model and is cancelled, while foreign
 * or third-party transactions match nothing and flow through untouched. KEEPALIVE
 * support is deleted by design — only the dedicated Play PING/PONG channel is
 * used.
 *
 * <p>Netty discipline: the PONG type is compared by REFERENCE against the
 * {@code Play.Client} constant (configuration has its own PONG that must never
 * match — cancelling it would time the client out mid-(re)configuration);
 * identity is the {@link User}'s UUID. In 4A1 nothing sends probes, so
 * {@code onResponse} matches nothing and this listener cancels nothing.</p>
 */
public final class ProbeRim extends PacketListenerAbstract {

    private final LatencyModel latency;

    public ProbeRim(LatencyModel latency) {
        super(PacketListenerPriority.LOWEST);
        this.latency = latency;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.PONG) {
            return;
        }
        User user = event.getUser();
        if (user == null || user.getUUID() == null) {
            return;
        }
        try {
            UUID id = user.getUUID();
            long probeId = Integer.toUnsignedLong(new WrapperPlayClientPong(event).getId());
            if (latency.forPlayer(id).onResponse(probeId, System.nanoTime())) {
                event.setCancelled(true);
            }
        } catch (Throwable ignored) {
            // Never throw from a packet handler; an unmatched probe is harmless.
        }
    }
}
